/*
 * Copyright (c) 2015, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

import jdk.internal.jrtfs.NewImage;
import jdk.internal.jrtfs.NewImage.Node;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * @test
 * @bug 8352750
 * @summary Tests NewImage prototype.
 * @modules java.base/jdk.internal.jrtfs
 * @run junit/othervm NewImageTest
 */
public class NewImageTest {
    @Test
    public void testBasicLazyNodeCreation() {
        Set<String> files = Set.of(
                "mod.one/java/foo/Foo.class",
                "mod.two/java/bar/Bar.class");
        TestImage image = new TestImage(false, files, Set.of());

        Node node = image.assertFirstLookup("/modules/mod.one/java/foo/Foo.class");
        assertFalse(node.isDirectory(), "Node: " + node);
        assertEquals("mod.one/java/foo/Foo.class", new String(node.loadResource(), UTF_8), "Node: " + node);
        image.assertNotLogged("/modules/mod.one/java/foo");

        image.assertNotLogged("/modules/mod.two");
        Node modLink = image.assertNode("/packages/java.bar/mod.two");
        image.assertLogged("/modules/mod.two");
        image.assertNotLogged("/modules/mod.two/java");

        Node modTwo = image.assertNode("/modules/mod.two");
        assertEquals(modTwo, modLink.resolve());

        Node modOne = image.assertFirstLookup("/modules/mod.one");
        Node modRoot = image.assertFirstLookup("/modules");
        assertEquals(asList(modOne, modTwo), modRoot.getChildren());
    }

    @Test
    public void testPreviewFileReplace() {
        Set<String> files = Set.of(
                "mod.name/java/foo/First",
                "mod.name/java/foo/Second",
                "mod.name/java/foo/Third");
        Set<String> preview = Set.of(
                "mod.name/java/foo/Second");
        TestImage image = new TestImage(true, files, preview);

        Node first = image.assertFirstLookup("/modules/mod.name/java/foo/First");
        Node second = image.assertFirstLookup("/modules/mod.name/java/foo/Second");
        Node third = image.assertFirstLookup("/modules/mod.name/java/foo/Third");
        Node dir = image.assertFirstLookup("/modules/mod.name/java/foo");
        assertTrue(dir.isDirectory());
        assertEquals(asList(first, second, third), dir.getChildren());

        // Trailing '*' indicates it came from preview files.
        assertContent("mod.name/java/foo/First", first);
        assertContent("mod.name/java/foo/Second*", second);
        assertContent("mod.name/java/foo/Third", third);

        // Node exists without replaced content in no-preview image.
        TestImage noPreview = new TestImage(false, files, preview);
        assertContent("mod.name/java/foo/Second",
                noPreview.assertNode("/modules/mod.name/java/foo/Second"));
    }

    @Test
    public void testPreviewFileAddition() {
        Set<String> files = Set.of(
                "mod.name/java/foo/First",
                "mod.name/java/foo/Third");
        Set<String> preview = Set.of(
                "mod.name/java/foo/Second",
                "mod.name/java/foo/Xtra");
        TestImage image = new TestImage(true, files, preview);

        Node first = image.assertFirstLookup("/modules/mod.name/java/foo/First");
        Node second = image.assertFirstLookup("/modules/mod.name/java/foo/Second");
        Node third = image.assertFirstLookup("/modules/mod.name/java/foo/Third");
        Node last = image.assertFirstLookup("/modules/mod.name/java/foo/Xtra");
        Node dir = image.assertFirstLookup("/modules/mod.name/java/foo");
        assertTrue(dir.isDirectory());
        assertEquals(asList(first, second, third, last), dir.getChildren());

        // Trailing '*' indicates it came from preview files.
        assertContent("mod.name/java/foo/First", first);
        assertContent("mod.name/java/foo/Second*", second);
        assertContent("mod.name/java/foo/Third", third);
        assertContent("mod.name/java/foo/Xtra*", last);

        // Preview nodes are missing in non-preview image.
        TestImage noPreview = new TestImage(false, files, preview);
        assertTrue(noPreview.get("/modules/mod.name/java/foo/First").isPresent());
        assertFalse(noPreview.get("/modules/mod.name/java/foo/Second").isPresent());
        assertTrue(noPreview.get("/modules/mod.name/java/foo/Third").isPresent());
        assertFalse(noPreview.get("/modules/mod.name/java/foo/Xtra").isPresent());
    }

    @Test
    public void testPreviewDirectoryAddition() {
        Set<String> files = Set.of(
                "mod.name/java/foo/First",
                "mod.name/java/foo/Second");
        Set<String> preview = Set.of(
                "mod.name/java/foo/bar/SubDirFile",
                "mod.name/java/gus/OtherDirFile");
        TestImage image = new TestImage(true, files, preview);

        Node first = image.assertFirstLookup("/modules/mod.name/java/foo/First");
        Node second = image.assertFirstLookup("/modules/mod.name/java/foo/Second");
        Node subDir = image.assertFirstLookup("/modules/mod.name/java/foo/bar");
        assertTrue(subDir.isDirectory());
        Node dir = image.assertFirstLookup("/modules/mod.name/java/foo");
        assertEquals(asList(first, second, subDir), dir.getChildren());

        image.assertFirstLookup("/modules/mod.name/java/foo/bar/SubDirFile");
        image.assertFirstLookup("/modules/mod.name/java/gus/OtherDirFile");
        image.assertNode("/packages/java.gus/mod.name");

        // Preview nodes are missing in non-preview image.
        TestImage noPreview = new TestImage(false, files, preview);
        assertFalse(noPreview.get("/modules/mod.name/java/foo/bar").isPresent());
        assertFalse(noPreview.get("/modules/mod.name/java/gus").isPresent());
        assertFalse(noPreview.get("/packages/java.gus/mod.name").isPresent());
    }

    @Test
    public void testTopLevelNonDirectory() {
        Set<String> files = Set.of(
                "mod.name/java/foo/First",
                "not.a.directory",
                "mod.name/java/foo/Second");
        Set<String> preview = Set.of(
                "normal.file",
                "mod.name/java/bar/Other");
        TestImage image = new TestImage(true, files, preview);

        // Top level non-directory files can exist (they probably should IRL) but
        // they are not implied to be module names.
        assertFalse(image.assertFirstLookup("/modules/not.a.directory").isDirectory());
        assertFalse(image.assertFirstLookup("/modules/normal.file").isDirectory());

        Node packages = image.assertNode("/packages");
        Node pkgJava = image.assertNode("/packages/java");
        Node pkgFoo = image.assertNode("/packages/java.foo");
        Node pkgBar = image.assertNode("/packages/java.bar");
        assertEquals(asList(pkgJava, pkgBar, pkgFoo), packages.getChildren());
    }

    static void assertContent(String expected, Node node) {
        assertFalse(node.isDirectory());
        assertFalse(node.isLink());
        assertEquals(expected, new String(node.loadResource(), UTF_8), "Unexpected node content");
    }

    static class TestImage extends NewImage {
        private final Map<String, Boolean> previewMap = new LinkedHashMap<>();
        private final Map<String, Boolean> fileMap = new LinkedHashMap<>();
        private final Set<String> allModuleNames;
        private final Set<String> allPackageNames;
        // Log of created resources (by path string). Useful to demonstrate lazy creation.
        private final Set<String> creationLog = new LinkedHashSet<>();

        protected TestImage(boolean isPreviewMode, Set<String> resourceFiles, Set<String> previewFiles) {
            super(isPreviewMode);
            fillMap(this.fileMap, resourceFiles);
            fillMap(this.previewMap, previewFiles);
            this.allModuleNames = Collections.unmodifiableSet(getModuleNames());
            this.allPackageNames = Collections.unmodifiableSet(getPackageNames());
        }

        // ---- Test helper method for common assertions ----

        Node assertFirstLookup(String path) {
            ensureModulePath(path, "Can only assert first lookup for module resources");
            assertNotLogged(path);
            Node node = assertNode(path);
            assertLogged(path);
            return node;
        }

        Node assertNode(String path) {
            Optional<Node> node = get(path);
            assertTrue(node.isPresent(), "Missing node: " + path);
            return node.get();
        }

        void assertLogged(String path) {
            ensureModulePath(path, "Can only assert log entries for module resources");
            assertTrue(
                    creationLog.contains(path),
                    "Expected logged path: " + path + "\nLogs:\n\t" + String.join("\n\t", creationLog));
        }

        void assertNotLogged(String path) {
            ensureModulePath(path, "Can only assert log entries for module resources");
            assertFalse(
                    creationLog.contains(path),
                    "Did not expect logged path: " + path + "\nLogs:\n\t" + String.join("\n\t", creationLog));
        }

        private void ensureModulePath(String path, String msg) {
            if (!path.equals("/modules") && !path.startsWith("/modules/")) {
                throw new IllegalArgumentException(msg + ": " + path);
            }
        }

        private Node log(Node newNode) {
            creationLog.add(newNode.toString());
            return newNode;
        }

        // ---- Populating test data ----

        private static void fillMap(Map<String, Boolean> map, Set<String> files) {
            assertTrue(files.stream().allMatch(p -> !p.isEmpty() && !p.startsWith("/") && !p.endsWith("/") && !p.contains("//")));
            files.stream().flatMap(TestImage::parentDirs).forEach(d -> map.put(d, true));
            files.forEach(f -> map.put(f, false));
        }

        private static Stream<String> parentDirs(String path) {
            return Stream.iterate(
                    path,
                    p -> !p.isEmpty(),
                    p -> p.substring(0, Math.max(p.lastIndexOf('/'), 0)));
        }

        private Set<String> getModuleNames() {
            Set<String> moduleNames = new TreeSet<>();
            BiConsumer<String, Boolean> addModuleName = (p, isDir) -> {
                if (isDir && !p.contains("/")) {
                    moduleNames.add(p);
                }
            };
            this.fileMap.forEach(addModuleName);
            this.previewMap.forEach(addModuleName);
            return moduleNames;
        }

        private Set<String> getPackageNames() {
            Set<String> packageNames = new TreeSet<>();
            BiConsumer<String, Boolean> addPackageName = (p, isDir) -> {
                if (isDir) {
                    int sepIdx = p.indexOf('/');
                    if (sepIdx >= 0) {
                        packageNames.add(p.substring(sepIdx + 1).replace('/', '.'));
                    }
                }
            };
            this.fileMap.forEach(addPackageName);
            this.previewMap.forEach(addPackageName);
            return packageNames;
        }

        // ---- Test image logic ----

        private Node logNewResource(Path path, boolean isDir, boolean isPreview) {
            return log(isDir
                    ? newResourceDirectory(path)
                    : newResource(path, () -> (path + (isPreview ? "*" : "")).getBytes(UTF_8)));
        }

        @Override
        protected Optional<Node> getResource(Path resourcePath, boolean preview) {
            String pathString = resourcePath.toString();
            if (pathString.isEmpty()) {
                // "Root" directory always exists for path "".
                return Optional.of(logNewResource(resourcePath, true, preview));
            }
            Boolean isDir = (preview ? previewMap : fileMap).get(pathString);
            return isDir != null
                    ? Optional.of(logNewResource(resourcePath, isDir, preview))
                    : Optional.empty();
        }

        @Override
        protected void forEachChildOf(Path resourcePath, boolean preview, Consumer<Node> action) {
            forEachChild(resourcePath.toString(), preview, (name, isDir) -> {
                Path p = resourcePath.resolve(name);
                action.accept(logNewResource(p, isDir, preview));
            });
        }

        @Override
        protected Set<String> getAllModuleNames() {
            return allModuleNames;
        }

        @Override
        protected Set<String> getAllPackageNames() {
            return allPackageNames;
        }

        private void forEachChild(String dirStr, boolean preview, BiConsumer<String, Boolean> action) {
            // Root directory prefix is just "", not "/".
            String dirPrefix = dirStr.isEmpty() ? dirStr : dirStr + "/";
            Map<String, Boolean> map = preview ? previewMap : fileMap;
            for (var e : map.entrySet()) {
                String p = e.getKey();
                if (p.startsWith(dirPrefix) && p.indexOf('/', dirPrefix.length() + 1) == -1) {
                    action.accept(p.substring(dirPrefix.length()), e.getValue());
                }
            }
        }

        @Override
        public String toString() {
            return String.format(
                    "Files: %s\nPreview: %s\nModules: %s\n, Packages: %s\nLog: %s",
                    fileMap, previewMap, allModuleNames, allPackageNames, creationLog);
        }
    }
}
