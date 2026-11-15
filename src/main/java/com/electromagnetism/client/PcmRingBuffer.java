package com.electromagnetism.client;

/** Bounded PCM queue shared by the client and audio threads. Overflow drops oldest samples. */
public final class PcmRingBuffer {
    private final short[] samples;
    private int head, size;
    public PcmRingBuffer(int capacity) {
        if(capacity<=0)throw new IllegalArgumentException("Positive PCM capacity required");
        samples=new short[capacity];
    }
    public synchronized int size(){return size;}
    public synchronized boolean isEmpty(){return size==0;}
    public synchronized void clear(){head=0;size=0;}
    public synchronized short removeFirst(){
        if(size==0)throw new java.util.NoSuchElementException();
        short value=samples[head];head=(head+1)%samples.length;size--;return value;
    }
    public synchronized void addLast(short value){
        if(size==samples.length){head=(head+1)%samples.length;size--;}
        samples[(head+size)%samples.length]=value;size++;
    }
}
