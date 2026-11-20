package com.electromagnetism.simulation;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Native library loading and copied batch transport shared by production and verification. */
public final class NativeLinearBridge {
    private static Path packagedLibrary;
    private NativeLinearBridge() {}

    public static void load(Path library) {
        System.load(library.toAbsolutePath().normalize().toString());
    }

    /** Loads the Windows x64 release artifact from the mod JAR, not the development tree.
     * Loading alone does not declare a world's device inventory ready.
     * Cache paths are content hashes, not additional protocol or compatibility versions.
     */
    public static synchronized Path loadPackaged() throws IOException {
        if (packagedLibrary != null) return packagedLibrary;
        String arch = System.getProperty("os.arch");
        if (!System.getProperty("os.name").startsWith("Windows")
                || !(arch.equals("amd64") || arch.equals("x86_64")))
            throw new UnsupportedOperationException("Native release currently supports Windows x64 only");
        byte[] bytes;
        try (var input = NativeLinearBridge.class.getResourceAsStream(
                "/native/windows-x86_64/electromagnetism_native.dll")) {
            if (input == null) throw new IOException("Packaged native library is missing");
            bytes = input.readNBytes(32 * 1024 * 1024);
            if (bytes.length == 0 || input.read() != -1) throw new IOException("Invalid native library size");
        }
        String hash = digest(bytes);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "electromagnetism-native", hash);
        Files.createDirectories(directory);
        Path library = directory.resolve("electromagnetism_native.dll");
        if (!Files.exists(library)) {
            Path temporary = Files.createTempFile(directory,"extract-",".dll");
            try {
                Files.write(temporary,bytes);
                try { Files.move(temporary,library); }
                catch (FileAlreadyExistsException concurrentExtraction) { /* Verify the winner below. */ }
            } finally { Files.deleteIfExists(temporary); }
        }
        if (Files.size(library) != bytes.length || !digest(Files.readAllBytes(library)).equals(hash))
            throw new IOException("Cached native library content does not match packaged artifact: " + library);
        load(library);
        packagedLibrary = library.toAbsolutePath().normalize();
        return packagedLibrary;
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Synchronous copied input/output; format is documented in docs/native-wire.md. */
    public static byte[] exchange(byte[] request) {
        return exchange(request, false);
    }

    static byte[] exchange(byte[] request, boolean resumedTick) {
        boolean helper = FaultDomainManager.tryAcquireHelper(resumedTick);
        try { return exchangeBatch(request, helper); }
        finally { if (helper) FaultDomainManager.releaseHelper(); }
    }

    private static native byte[] exchangeBatch(byte[] request, boolean helper);
}
