package com.electromagnetism.simulation;

/**
 * Run the JNI contract checks with the bridge class and DLL loaded exclusively from the release
 * JAR.
 */
public final class NativePackagedBridgeChecks {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("--corrupt")) {
            verifyCorruptCache();
            return;
        }
        var location = NativeLinearBridge.class.getProtectionDomain().getCodeSource().getLocation();
        if (!location.getPath().endsWith(".jar")) throw new AssertionError("Bridge was not loaded from release JAR");
        verifyMissingLibrary();
        var cache = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"corrupt-check-");
        var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                "-Djava.io.tmpdir=" + cache, "-cp", System.getProperty("java.class.path"),
                NativePackagedBridgeChecks.class.getName(), "--corrupt").inheritIO().start();
        if (!child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly();throw new AssertionError("Corrupt cache check timed out");
        }
        if (child.exitValue() != 0) throw new AssertionError("Corrupt cache accepted");
        var library = NativeLinearBridge.loadPackaged();
        if (!library.equals(NativeLinearBridge.loadPackaged())) throw new AssertionError("Repeated loading changed path");
        System.setProperty("electromagnetism.nativeLibrary", library.toString());
        NativeBridgeChecks.main(args);
        System.out.println("PACKAGED_NATIVE_BRIDGE_PASSED: release JAR extraction and real JNI physics");
    }

    private static void verifyCorruptCache() throws Exception {
        byte[] dll;
        try (var input = NativeLinearBridge.class.getResourceAsStream("/native/windows-x86_64/electromagnetism_native.dll")) {
            if (input == null) throw new AssertionError("Release DLL missing");
            dll = input.readAllBytes();
        }
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(dll));
        var directory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"electromagnetism-native",hash);
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.write(directory.resolve("electromagnetism_native.dll"),new byte[]{0});
        try { NativeLinearBridge.loadPackaged();throw new AssertionError("Corrupt DLL loaded"); }
        catch (java.io.IOException expected) {
            if (!expected.getMessage().contains("does not match")) throw expected;
        }
    }

    private static void verifyMissingLibrary() throws Exception {
        var jar = java.nio.file.Files.createTempFile(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"missing-native-",".jar");
        String name = "com/electromagnetism/simulation/NativeLinearBridge.class";
        try {
            try (var output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar));
                 var input = NativeLinearBridge.class.getResourceAsStream("/" + name)) {
                if (input == null) throw new AssertionError("Bridge class missing");
                output.putNextEntry(new java.util.jar.JarEntry(name));input.transferTo(output);output.closeEntry();
            }
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                var bridge = loader.loadClass(NativeLinearBridge.class.getName());
                try { bridge.getMethod("loadPackaged").invoke(null);throw new AssertionError("Missing DLL accepted"); }
                catch (java.lang.reflect.InvocationTargetException failure) {
                    if (!(failure.getCause() instanceof java.io.IOException)
                            || !failure.getCause().getMessage().contains("missing")) throw failure;
                }
            }
        } finally { java.nio.file.Files.deleteIfExists(jar); }
    }
}
