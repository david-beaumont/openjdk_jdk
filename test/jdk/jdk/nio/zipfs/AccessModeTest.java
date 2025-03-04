/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @test
 * @bug 8350880
 * @summary Test that Zip FS honours access modes as expected.
 * @modules jdk.zipfs
 * @run junit AccessModeTest
 */
public class AccessModeTest {
    @Test
    public void defaultModeIsWritableWhenNoFileExists() throws IOException {
        Path zipPath = Path.of("missing.zip");
        assertFalse(Files.exists(zipPath));

        try (FileSystem fs = FileSystems.newFileSystem(zipPath, Map.of("create", true))) {
            assertFalse(fs.isReadOnly());
            assertTrue(fs.isOpen());
            Path path = fs.getPath("new_file.txt");
            OutputStream os = Files.newOutputStream(path);
            os.write("Hello World".getBytes(UTF_8));
            os.close();
        }
        assertTrue(Files.exists(zipPath));
    }

    @Test
    public void readOnlyZipFilesHaveReadOnlyAccess() throws IOException {
        Path zipPath = Path.of("readonly.zip");
        Utils.createJarFile(zipPath, "foo");
        assertTrue(zipPath.toFile().setReadOnly());

        assertThrows(IllegalArgumentException.class,
                () -> FileSystems.newFileSystem(zipPath, Map.of("accessMode", "readWrite")));

        try (FileSystem fs = FileSystems.newFileSystem(zipPath)) {
            assertTrue(fs.isReadOnly());
        }
    }

    @Test
    public void multiReleaseJarsHaveReadOnlyAccess() throws IOException {
        Path jarPath = Path.of("multi_release.jar");
        createMultiReleaseTestJar(jarPath);
        assertTrue(Files.isWritable(jarPath));

        assertThrows(IllegalArgumentException.class,
                () -> FileSystems.newFileSystem(jarPath, Map.of("accessMode", "readWrite", "releaseVersion", 21)));

        try (FileSystem fs = FileSystems.newFileSystem(jarPath, Map.of("releaseVersion", 21))) {
            assertTrue(fs.isReadOnly());
        }
    }

    @Test
    public void readOnlyModeCannotOpenOutputStreams() throws IOException {
        Path zipPath = Path.of("opened_readonly.zip");
        Utils.createJarFile(zipPath, "foo.txt");
        assertTrue(Files.isWritable(zipPath));

        try (FileSystem fs = FileSystems.newFileSystem(zipPath, Map.of("accessMode", "readOnly"))) {
            assertTrue(fs.isReadOnly());
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.newOutputStream(fs.getPath("foo.txt")));
        }
    }

    @Test
    public void readOnlyModeFailsIfCreateIsSpecified() throws IOException {
        Path zipPath = Path.of("ignored.zip");
        Map<String, ?> badArgs = Map.of("accessMode", "readOnly", "create", true);
        assertThrows(IllegalArgumentException.class, () -> FileSystems.newFileSystem(zipPath, badArgs));
    }

    @Test
    public void posixPermissionsUnchangedInReadOnlyMode() throws IOException {
        Path rwPath = Path.of("posix_readwrite.zip");
        Path roPath = Path.of("posix_readonly.zip");
        Utils.createJarFile(rwPath, "foo.txt");
        Utils.createJarFile(roPath, "foo.txt");
        assertTrue(Files.isWritable(rwPath));
        assertTrue(Files.isWritable(roPath));

        Map<String, ?> rwPosix = Map.of("accessMode", "readWrite", "enablePosixFileAttributes", true);
        try (FileSystem rwFs = FileSystems.newFileSystem(rwPath, rwPosix)) {
            assertFalse(rwFs.isReadOnly());
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(rwFs.getPath("foo.txt"));
            assertTrue(permissions.contains(OWNER_WRITE));
            assertTrue(permissions.contains(GROUP_WRITE));
            assertTrue(permissions.contains(OTHERS_WRITE));
        }

        Map<String, ?> roPosix = Map.of("accessMode", "readOnly", "enablePosixFileAttributes", true);
        try (FileSystem roFs = FileSystems.newFileSystem(roPath, roPosix)) {
            assertTrue(roFs.isReadOnly());
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(roFs.getPath("foo.txt"));
            assertTrue(permissions.contains(OWNER_WRITE));
            assertTrue(permissions.contains(GROUP_WRITE));
            assertTrue(permissions.contains(OTHERS_WRITE));
        }
    }

    static void createMultiReleaseTestJar(Path jarPath) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(jarPath, Map.of("create", true))) {
            assertFalse(fs.isReadOnly());
            Files.createDirectory(fs.getPath("META-INF"));
            try (OutputStream os = Files.newOutputStream(fs.getPath("META-INF/MANIFEST.MF"))) {
                PrintWriter out = new PrintWriter(os, true, UTF_8);
                out.println("Multi-Release: true");
            }
        }
        assertTrue(Files.exists(jarPath));
    }
}
