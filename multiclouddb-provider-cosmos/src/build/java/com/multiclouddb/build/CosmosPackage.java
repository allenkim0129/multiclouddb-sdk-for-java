// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Build-only resource preservation and narrow post-shade repairs. Never shipped. */
public final class CosmosPackage {
    private static final String ORIGINAL = "META-INF/io.netty.versions.properties";
    private static final String PRIVATE = "META-INF/multiclouddb-cosmos/netty.versions.properties";
    private static final String PREFIX = "com/multiclouddb/provider/cosmos/shaded/";
    private static final Pattern NATIVE = Pattern.compile("META-INF/native/(lib)?netty_[^/]+");
    private static final Pattern NOTICE = Pattern.compile(
            "(?i)(^|/)(license[^/]*|notice[^/]*|AL2\\.0|LGPL[^/]*)$");

    private CosmosPackage() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("prepare")) {
            prepare(Path.of(args[1]), args[2]);
        } else if (args.length == 2 && args[0].equals("finish")) {
            finish(Path.of(args[1]));
        } else {
            throw new IllegalArgumentException("Expected prepare <output> <classpath> or finish <jar>.");
        }
    }

    private static void prepare(Path output, String classpath) throws IOException {
        Path licenses = output.resolve("META-INF/multiclouddb-cosmos/licenses");
        if (Files.exists(licenses)) {
            try (var paths = Files.walk(licenses)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Path nativeDirectory = output.resolve("META-INF/native");
        if (Files.isDirectory(nativeDirectory)) {
            try (var paths = Files.list(nativeDirectory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString()
                        .matches("(lib)?com_multiclouddb_provider_cosmos_shaded_netty_[^/]+")).toList()) {
                    Files.delete(path);
                }
            }
        }
        int artifacts = 0;
        int natives = 0;
        for (String element : classpath.split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(element);
            String artifact = path.getFileName().toString();
            if (artifact.startsWith("multiclouddb-api-") || artifact.startsWith("slf4j-")
                    || artifact.startsWith("gson-") || artifact.startsWith("error_prone_annotations-")
                    || artifact.startsWith("jna-")) continue;
            if (!artifact.endsWith(".jar")) throw new IOException("Expected packaged runtime dependency: " + path);
            artifacts++;
            try (ZipFile jar = new ZipFile(path.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    String target;
                    if (NATIVE.matcher(name).matches()) {
                        target = nativeAlias(name);
                        natives++;
                    } else if (NOTICE.matcher(name).find()) {
                        target = "META-INF/multiclouddb-cosmos/licenses/" + artifact + "/" + name;
                    } else {
                        continue;
                    }
                    Path destination = output.resolve(target).normalize();
                    if (!destination.startsWith(output.normalize())) throw new IOException("Invalid resource path.");
                    Files.createDirectories(destination.getParent());
                    try (var stream = jar.getInputStream(entry)) {
                        Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (artifacts == 0 || natives == 0) throw new IOException("Missing Cosmos runtime/native resources.");
        System.out.println("Preserved notices from " + artifacts + " dependencies and " + natives + " native aliases.");
    }

    private static String nativeAlias(String name) {
        return name.replaceFirst("/(lib)?netty_", "/$1com_multiclouddb_provider_cosmos_shaded_netty_");
    }

    private static void finish(Path jar) throws IOException {
        Path output = jar.resolveSibling(jar.getFileName() + ".packaging-tmp");
        int[] lookups = {0};
        int metadata = 0;
        int natives = 0;
        int versioned = 0;
        Set<String> names = new HashSet<>();
        try {
            try (ZipFile input = new ZipFile(jar.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    byte[] bytes;
                    try (var stream = input.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }
                    if (NATIVE.matcher(name).matches()) {
                        ZipEntry alias = input.getEntry(nativeAlias(name));
                        if (alias == null) throw new IOException("Missing native alias: " + name);
                        try (var stream = input.getInputStream(alias)) {
                            if (!Arrays.equals(bytes, stream.readAllBytes())) {
                                throw new IOException("Native alias differs: " + name);
                            }
                        }
                        natives++;
                        continue;
                    }
                    if (name.startsWith("META-INF/versions/") && name.endsWith(".class")) {
                        int versionEnd = name.indexOf('/', "META-INF/versions/".length());
                        name = name.substring(0, versionEnd + 1) + new ClassReader(bytes).getClassName() + ".class";
                        versioned++;
                    }
                    if (name.endsWith(".class") && !new ClassReader(bytes).getClassName()
                            .startsWith("com/multiclouddb/provider/cosmos/")) {
                        throw new IOException("Unrelocated or shared class in private artifact: " + name);
                    }
                    if (name.equals(ORIGINAL)) {
                        name = PRIVATE;
                        metadata++;
                    }
                    if (name.equals(PREFIX + "io/netty/util/Version.class")) {
                        ClassReader reader = new ClassReader(bytes);
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(int access, String method, String descriptor,
                                    String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9,
                                        super.visitMethod(access, method, descriptor, signature, exceptions)) {
                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        if (ORIGINAL.equals(value)) {
                                            lookups[0]++;
                                            super.visitLdcInsn(PRIVATE);
                                        } else {
                                            super.visitLdcInsn(value);
                                        }
                                    }
                                };
                            }
                        }, 0);
                        bytes = writer.toByteArray();
                    }
                    if (!names.add(name)) throw new IOException("Duplicate packaged entry: " + name);
                    ZipEntry replacement = new ZipEntry(name);
                    replacement.setTime(entry.getTime());
                    out.putNextEntry(replacement);
                    out.write(bytes);
                    out.closeEntry();
                }
            }
            if (metadata != 1 || lookups[0] != 1 || natives == 0 || versioned == 0) {
                throw new IOException("Unexpected shaded resource layout: metadata=" + metadata
                        + ", lookups=" + lookups[0] + ", natives=" + natives + ", versioned=" + versioned);
            }
            Files.move(output, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(output);
        }
        System.out.println("Verified " + natives + " private native aliases, " + versioned
                + " multi-release classes and the private Netty metadata lookup.");
    }
}
