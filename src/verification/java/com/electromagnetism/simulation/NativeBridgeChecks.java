package com.electromagnetism.simulation;

import java.nio.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.*;

/** Real JVM -> DLL -> Rust RC solve; independent of Minecraft startup. */
public final class NativeBridgeChecks {
    private static final int MAGIC = 0x424e4d45;
    private static final byte[] MOD_VERSION=System.getProperty("electromagnetism.modVersion").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static ByteBuffer header(int operation, long handle) {
        return header(operation, handle, 4096);
    }
    private static ByteBuffer header(int operation, long handle, int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).putInt(MOD_VERSION.length).put(MOD_VERSION).putInt(operation).putLong(handle);
    }
    private static byte[] bytes(ByteBuffer b) { return Arrays.copyOf(b.array(), b.position()); }
    private static void revision(ByteBuffer b, long sequence) {
        b.putLong(5).putLong(1).putLong(1).putLong(1).putLong(sequence);
    }
    private static void port(ByteBuffer b, long id) { b.putLong(0).putLong(id).putLong(0).putInt(0); }
    private static void branch(ByteBuffer b, long id, long a, long z, int model, double parameter) {
        b.putLong(1).putLong(id); port(b, a); port(b, z);
        b.putInt(model).putDouble(parameter).putDouble(0);
    }
    private static byte[] createRequest() {
        var b=header(1,0); revision(b,0); b.putDouble(0.001).putInt(1).putInt(3);
        branch(b,1,1,0,2,1); branch(b,2,1,2,1,1); branch(b,3,2,0,3,1);
        return bytes(b);
    }
    private static byte[] adaptiveCreateRequest() {
        var b=header(8,0);revision(b,0);b.putDouble(1.0);
        for(int i=0;i<6;i++) b.putDouble(1e-5);
        // Two RC loads on the same supply exercise numerical step rejection.
        b.putDouble(1e-8).putInt(1).putInt(5);
        branch(b,1,1,0,2,1);branch(b,2,1,2,1,1);branch(b,3,2,0,3,1);
        branch(b,4,1,3,1,1);branch(b,5,3,0,3,1);
        return bytes(b);
    }
    private static byte[] diodeCreateRequest() {
        var b=header(1,0);revision(b,0);b.putDouble(0.0001).putInt(0).putInt(3);
        branch(b,1,1,0,6,0);b.putDouble(1).putDouble(10).putDouble(0);
        branch(b,2,1,2,1,1000);
        branch(b,3,2,0,5,1e-12);b.putDouble(0.025).putDouble(0);
        return bytes(b);
    }
    private static byte[] advance(long handle,long sequence,double start,double end,int steps) {
        var b=header(2,handle); revision(b,sequence);
        return bytes(b.putDouble(start).putDouble(end).putInt(steps).putLong(10_000_000_000L));
    }
    private static ByteBuffer response(byte[] request, boolean success) {
        var b=ByteBuffer.wrap(NativeLinearBridge.exchange(request)).order(ByteOrder.LITTLE_ENDIAN);
        require(b.getInt()==MAGIC,"response header");
        int status=b.getInt(); int length=b.getInt();
        byte[] reason=new byte[length]; b.get(reason);
        require((status==0)==success,"response status: "+new String(reason,java.nio.charset.StandardCharsets.UTF_8));
        return b;
    }
    private static byte[] state(long handle) { var b=response(bytes(header(3,handle)),true); byte[] result=new byte[b.remaining()]; b.get(result); return result; }
    private static byte[] snapshot(long handle) {
        var b=response(bytes(header(6,handle)),true);byte[] snapshot=new byte[b.getInt()];b.get(snapshot);
        require(!b.hasRemaining(),"snapshot trailing bytes");return snapshot;
    }
    private static byte[] restore(long handle,byte[] snapshot) {
        var b=header(7,handle,snapshot.length+128);b.putInt(snapshot.length).put(snapshot);return bytes(b);
    }
    public static void main(String[] args) throws Exception {
        NativeLinearBridge.load(Path.of(System.getProperty("electromagnetism.nativeLibrary")));
        var info=response(bytes(header(0,0)),true);
        byte[] metadata=new byte[info.getInt()];info.get(metadata);
        require(Arrays.equals(metadata,MOD_VERSION) && info.getLong()==1,"mod metadata handshake");
        byte[] create=createRequest();
        for(int size=0;size<create.length;size++) response(Arrays.copyOf(create,size),false);
        byte[] bad=create.clone(); bad[4]=2; response(bad,false);
        bad=create.clone();bad[8]^=1;response(bad,false); // same-length mod metadata mismatch
        bad=Arrays.copyOf(create,create.length+1); response(bad,false);
        bad=create.clone(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putDouble(header(1,0).position()+40,Double.NaN); response(bad,false);
        response(new byte[1024*1024+1],false);
        try { NativeLinearBridge.exchange(null); throw new AssertionError("null accepted"); } catch(RuntimeException expected) { }
        long handle=response(create,true).getLong();
        response(create,false); // Cannot fork accepted ownership under one session identity.
        byte[] request=advance(handle,1,0,1,2000);
        byte[] first=NativeLinearBridge.exchange(request);
        require(Arrays.equals(first,NativeLinearBridge.exchange(request)),"duplicate result changed");
        var result=ByteBuffer.wrap(state(handle)).order(ByteOrder.LITTLE_ENDIAN);
        near(result.getDouble(),1,1e-15);
        double[] energy=new double[7]; for(int i=0;i<7;i++) energy[i]=result.getDouble();
        near(energy[6],0,1e-10); require(energy[5]>0,"numerical damping missing");
        require(result.getInt()==3,"branch count");
        for(int i=0;i<3;i++) {
            result.getLong(); long id=result.getLong(); double voltage=result.getDouble();
            if(id==3) near(voltage,1-Math.exp(-1),0.0002);
            for(int j=0;j<4;j++) result.getDouble();
        }
        byte[] before=state(handle);
        byte[] checkpoint=snapshot(handle);
        var acRequest=header(9,handle).putDouble(1/(2*Math.PI)).putInt(1)
                .putLong(1).putLong(1).putDouble(1).putDouble(0);
        byte[] acBytes=bytes(acRequest);
        for(int size=0;size<acBytes.length;size++) response(Arrays.copyOf(acBytes,size),false);
        var ac=response(acBytes,true);
        near(ac.getDouble(),1/(2*Math.PI),1e-15);
        require(ac.getInt()==3,"AC branch count");
        for(int i=0;i<3;i++) {
            ac.getLong();long id=ac.getLong();double real=ac.getDouble(),imaginary=ac.getDouble();
            if(id==3) { near(real,0.5,1e-12);near(imaginary,-0.5,1e-12); }
            ac.getDouble();ac.getDouble();
        }
        require(!ac.hasRemaining(),"AC trailing bytes");
        require(Arrays.equals(checkpoint,snapshot(handle)),"AC changed transient state or transaction cursor");
        response(advance(handle,1,0,2,2000),false);
        response(advance(handle,2,0,2,2000),false);
        require(Arrays.equals(before,state(handle)),"invalid request mutated state");
        response(bytes(header(5,handle)),true);
        response(advance(handle,2,1,2,2000),true);
        require(Arrays.equals(before,state(handle)),"cancel advanced state");
        response(bytes(header(4,handle)),true);
        response(bytes(header(4,handle)),false); response(bytes(header(3,handle)),false);
        long resumed=response(create,true).getLong();
        byte[] clean=state(resumed);
        byte[] corrupt=checkpoint.clone();corrupt[16]^=1; // inner mod metadata
        response(restore(resumed,corrupt),false);require(Arrays.equals(clean,state(resumed)),"failed restore changed state");
        response(restore(resumed,checkpoint),true);require(Arrays.equals(before,state(resumed)),"restored state differs");
        response(advance(resumed,1,0,1,2000),false); // saved watermark prevents replay
        require(Arrays.equals(before,state(resumed)),"restore replay changed energy");
        response(advance(resumed,2,1,2,2000),true);
        response(restore(resumed,checkpoint),false); // no rollback into a live session
        byte[] continued=state(resumed);
        response(bytes(header(4,resumed)),true);
        long reference=response(create,true).getLong();
        response(advance(reference,1,0,1,2000),true);response(advance(reference,2,1,2,2000),true);
        require(Arrays.equals(continued,state(reference)),"checkpoint continuation differs from uninterrupted solve");
        response(bytes(header(5,reference)),true);byte[] cancelledSnapshot=snapshot(reference);
        response(bytes(header(4,reference)),true);
        long cancelledRestore=response(create,true).getLong();response(restore(cancelledRestore,cancelledSnapshot),true);
        byte[] cancelledState=state(cancelledRestore);response(advance(cancelledRestore,3,2,3,2000),true);
        require(Arrays.equals(cancelledState,state(cancelledRestore)),"snapshot lost cancellation");
        response(bytes(header(4,cancelledRestore)),true);
        long concurrent=response(create,true).getLong();
        var executor=Executors.newSingleThreadExecutor();
        try {
            var task=executor.submit(()->NativeLinearBridge.exchange(advance(concurrent,1,0,1000,1_000_000)));
            response(bytes(header(4,concurrent)),true);
            task.get(15,TimeUnit.SECONDS); // Either stale before acquisition, or safe cancelled prefix.
            response(bytes(header(3,concurrent)),false);
        } finally { executor.shutdownNow(); }
        long adaptive=response(adaptiveCreateRequest(),true).getLong();
        byte[] adaptiveInitial=state(adaptive);response(advance(adaptive,1,0,1,1),true);
        require(Arrays.equals(adaptiveInitial,state(adaptive)),"rejected adaptive candidate committed");
        byte[] adaptiveSnapshot=snapshot(adaptive);response(bytes(header(4,adaptive)),true);
        long adaptiveRestored=response(create,true).getLong();response(restore(adaptiveRestored,adaptiveSnapshot),true);
        response(advance(adaptiveRestored,2,0,1,10000),true);
        var adaptiveState=ByteBuffer.wrap(state(adaptiveRestored)).order(ByteOrder.LITTLE_ENDIAN);
        near(adaptiveState.getDouble(),1,1e-15);
        adaptiveState.position(8+7*8+4+2*(16+5*8)+16);
        near(adaptiveState.getDouble(),1-Math.exp(-1),0.002);
        response(bytes(header(4,adaptiveRestored)),true);
        long diode=response(diodeCreateRequest(),true).getLong();
        response(advance(diode,1,0,0.025,10000),true);
        var diodeState=ByteBuffer.wrap(state(diode)).order(ByteOrder.LITTLE_ENDIAN);
        near(diodeState.getDouble(),0.025,1e-15);
        diodeState.position(8+7*8+4+2*(16+5*8)+16+8);
        require(diodeState.getDouble()>0.0004,"diode forward conduction");
        byte[] diodeSnapshot=snapshot(diode);response(bytes(header(4,diode)),true);
        long diodeRestored=response(create,true).getLong();response(restore(diodeRestored,diodeSnapshot),true);
        response(advance(diodeRestored,2,0.025,0.075,10000),true);
        var reverse=ByteBuffer.wrap(state(diodeRestored)).order(ByteOrder.LITTLE_ENDIAN);
        near(reverse.getDouble(),0.075,1e-15);
        reverse.position(8+7*8+4+2*(16+5*8)+16+8);near(reverse.getDouble(),-1e-12,1e-13);
        response(bytes(header(4,diodeRestored)),true);
        long edited=response(create,true).getLong();
        response(advance(edited,1,0,0.5,2000),true);
        byte[] editBefore=snapshot(edited);
        var edit=header(10,edited);
        edit.putLong(5).putLong(1).putLong(1).putLong(2).putLong(2);
        edit.putDouble(0.5).putInt(1).putLong(1).putLong(2).putInt(1).putDouble(2);
        byte[] editBytes=bytes(edit);
        for(int size=0;size<editBytes.length;size++) response(Arrays.copyOf(editBytes,size),false);
        require(Arrays.equals(editBefore,snapshot(edited)),"truncated parameter request mutated session");
        response(editBytes,true);byte[] editAfter=snapshot(edited);
        response(editBytes,true);require(Arrays.equals(editAfter,snapshot(edited)),"parameter replay changed state");
        byte[] conflicting=editBytes.clone();ByteBuffer.wrap(conflicting).order(ByteOrder.LITTLE_ENDIAN)
                .putDouble(conflicting.length-8,3);
        response(conflicting,false);require(Arrays.equals(editAfter,snapshot(edited)),"conflicting parameter replay changed state");
        response(advance(edited,3,0.5,1,2000),false);
        var next=header(2,edited).putLong(5).putLong(1).putLong(1).putLong(2).putLong(3)
                .putDouble(0.5).putDouble(1).putInt(2000).putLong(10_000_000_000L);
        response(bytes(next),true);
        var editState=ByteBuffer.wrap(state(edited)).order(ByteOrder.LITTLE_ENDIAN);
        editState.position(8+7*8+4+2*(16+5*8)+16);
        near(editState.getDouble(),1-Math.exp(-0.75),0.0003);
        byte[] beforeTopology=state(edited);
        var topology=header(11,edited).putLong(5).putLong(1).putLong(2).putLong(2).putLong(4)
                .putDouble(1).putInt(4);
        topologyBranch(topology,3,2,0,3,1);
        topologyBranch(topology,1,1,0,2,1);
        topologyBranch(topology,4,2,0,1,2);
        topologyBranch(topology,2,1,2,1,2);
        byte[] topologyBytes=bytes(topology);
        for(int size=0;size<topologyBytes.length;size++) response(Arrays.copyOf(topologyBytes,size),false);
        require(Arrays.equals(beforeTopology,state(edited)),"truncated topology request changed state");
        response(topologyBytes,true);byte[] topologySnapshot=snapshot(edited);
        response(topologyBytes,true);require(Arrays.equals(topologySnapshot,snapshot(edited)),"topology replay changed state");
        var topologyState=ByteBuffer.wrap(state(edited)).order(ByteOrder.LITTLE_ENDIAN);
        var priorState=ByteBuffer.wrap(beforeTopology).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<8;i++) near(topologyState.getDouble(),priorState.getDouble(),1e-12);
        require(topologyState.getInt()==4,"topology branch count");
        topologyState.getLong();require(topologyState.getLong()==3,"topology preserves requested branch ordering");
        priorState.position(8+7*8+4+2*(16+5*8)+16);
        near(topologyState.getDouble(),priorState.getDouble(),1e-12);
        response(bytes(header(4,edited)),true);
        verifyLargeIslands();
        NativeBackendChecks.run(Path.of(System.getProperty("electromagnetism.nativeLibrary")),
                System.getProperty("electromagnetism.modVersion"));
        System.out.println("Native JNI checks passed: real RC physics, energy, malformed batches, dedup, cancel, stale handles, concurrent destroy, atomic checkpoint restore and continuation");
    }
    private static void verifyLargeIslands() {
        var create=header(1,0,256*1024);revision(create,0);
        create.putDouble(0.001).putInt(1).putInt(1024);
        for(int island=0;island<256;island++) {
            long node=3L*island,id=4L*island;
            branch(create,id,node+1,node,2,1);
            branch(create,id+1,node+1,node+2,1,1);
            branch(create,id+2,node+2,node,3,1);
            branch(create,id+3,node+2,node,1,1);
        }
        long handle=response(bytes(create),true).getLong();
        try {
            byte[] request=advance(handle,1,0,0.05,2);
            byte[] first=NativeLinearBridge.exchange(request);
            require(Arrays.equals(first,NativeLinearBridge.exchange(request)),"large circuit duplicate request changed");
            var s=ByteBuffer.wrap(state(handle)).order(ByteOrder.LITTLE_ENDIAN);
            near(s.getDouble(),0.002,1e-15);
            for(int i=0;i<6;i++) s.getDouble();
            near(s.getDouble(),0,1e-10);
            require(s.getInt()==1024,"large circuit branch count");
            for(int i=0;i<1024;i++) {
                s.getLong();long id=s.getLong();double voltage=s.getDouble();
                if(id%4==2) near(voltage,0.5*(1-Math.pow(1.002,-2)),1e-12);
                for(int j=0;j<4;j++) s.getDouble();
            }
            near(s.getDouble(),0,1e-15);near(s.getDouble(),0,1e-15);
            require(!s.hasRemaining(),"large state trailing bytes");
            byte[] accepted=state(handle),checkpoint=snapshot(handle);
            response(bytes(header(4,handle)),true);
            handle=response(bytes(create),true).getLong();
            response(restore(handle,checkpoint),true);
            require(Arrays.equals(accepted,state(handle)),"large snapshot changed accepted state");
            response(request,false); // destroyed handle remains stale
        } finally { response(bytes(header(4,handle)),true); }
        System.out.println("Native JNI scale checks passed: 1024 branches, 256 RC islands, common accepted prefix and snapshot restore");
    }
    private static void near(double a,double b,double tolerance) { require(Math.abs(a-b)<=tolerance,"numeric mismatch "+a+" / "+b); }
    private static void topologyBranch(ByteBuffer b,long id,long a,long z,int model,double parameter) {
        b.putLong(1).putLong(id);port(b,a);port(b,z);b.putInt(model).putDouble(parameter);
    }
    private static void require(boolean ok,String reason) { if(!ok) throw new AssertionError(reason); }
}
