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

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

        // Normal file lookup does not create the containing directory.
        Node node = image.assertFirstLookup("/modules/mod.one/java/foo/Foo.class");
        assertFalse(node.isDirectory(), "Node: " + node);
        assertContent("mod.one/java/foo/Foo.class", node);
        image.assertNotLogged("/modules/mod.one/java/foo");

        // Normal directory lookup does not create child nodes.
        image.assertNotLogged("/modules/mod.two");
        Node modLink = image.assertNode("/packages/java.bar/mod.two");
        // Making a package link creates the linked directory ...
        image.assertLogged("/modules/mod.two");
        // ... but nothing inside it.
        image.assertNotLogged("/modules/mod.two/java");
        // Package links resolve to the linked node.
        Node modTwo = image.assertNode("/modules/mod.two");
        assertEquals(modTwo, modLink.resolveLink(false));

        // The root /modules directory is created only when requested.
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

        // There are 3 files in the directory.
        Node first = image.assertFirstLookup("/modules/mod.name/java/foo/First");
        Node second = image.assertFirstLookup("/modules/mod.name/java/foo/Second");
        Node third = image.assertFirstLookup("/modules/mod.name/java/foo/Third");
        Node dir = image.assertFirstLookup("/modules/mod.name/java/foo");
        assertTrue(dir.isDirectory());
        assertEquals(asList(first, second, third), dir.getChildren());

        // But one of them comes from the preview set (the trailing '*').
        assertContent("mod.name/java/foo/First", first);
        assertContent("mod.name/java/foo/Second*", second);
        assertContent("mod.name/java/foo/Third", third);

        // This node exists without replaced content in no-preview image.
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

        // There are 4 files in the directory.
        Node first = image.assertFirstLookup("/modules/mod.name/java/foo/First");
        Node second = image.assertFirstLookup("/modules/mod.name/java/foo/Second");
        Node third = image.assertFirstLookup("/modules/mod.name/java/foo/Third");
        Node last = image.assertFirstLookup("/modules/mod.name/java/foo/Xtra");
        Node dir = image.assertFirstLookup("/modules/mod.name/java/foo");
        assertTrue(dir.isDirectory());
        assertEquals(asList(first, second, third, last), dir.getChildren());

        // Two are from the preview set (the trailing '*').
        assertContent("mod.name/java/foo/First", first);
        assertContent("mod.name/java/foo/Second*", second);
        assertContent("mod.name/java/foo/Third", third);
        assertContent("mod.name/java/foo/Xtra*", last);

        // The preview nodes are missing in the non-preview image.
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

        // A new directory was added from the preview set.
        Node first = image.assertFirstLookup("/modules/mod.name/java/foo/First");
        Node second = image.assertFirstLookup("/modules/mod.name/java/foo/Second");
        Node subDir = image.assertFirstLookup("/modules/mod.name/java/foo/bar");
        assertTrue(subDir.isDirectory());
        Node dir = image.assertFirstLookup("/modules/mod.name/java/foo");
        assertEquals(asList(first, second, subDir), dir.getChildren());

        // Preview files may create entirely new directories.
        image.assertFirstLookup("/modules/mod.name/java/foo/bar/SubDirFile");
        image.assertFirstLookup("/modules/mod.name/java/gus/OtherDirFile");
        // And new packages inferred by them are reflected correctly in /packages
        image.assertNode("/packages/java.gus/mod.name");

        // None of this appears in the non-preview image.
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

        // Top level non-directory files can exist (they probably shouldn't IRL),
        // but they are not implied to be module names.
        assertFalse(image.assertFirstLookup("/modules/not.a.directory").isDirectory());
        assertFalse(image.assertFirstLookup("/modules/normal.file").isDirectory());

        Node packages = image.assertNode("/packages");
        Node pkgJava = image.assertNode("/packages/java");
        Node pkgFoo = image.assertNode("/packages/java.foo");
        Node pkgBar = image.assertNode("/packages/java.bar");
        assertEquals(asList(pkgJava, pkgBar, pkgFoo), packages.getChildren());
    }

    @Test
    public void testBadPaths() {
        Set<String> files = Set.of(
                "mod.name/java/foo/First",
                "mod.name/java/foo/Second");
        TestImage image = new TestImage(false, files, Set.of());

        // Test good paths first to prove something is working!
        List<String> goodPaths = asList(
                "",
                "/modules",
                "/modules/mod.name",
                "/modules/mod.name/java",
                "/modules/mod.name/java/foo",
                "/modules/mod.name/java/foo/First",
                "/packages",
                "/packages/java.foo",
                "/packages/java.foo/mod.name");
        for (String path : goodPaths) {
            assertTrue(image.get(path).isPresent(), "Good path should be present: " + path);
        }

        // None of these should result in an exception (users are allowed to ask for anything).
        List<String> badPaths = asList(
                // Always invalid paths.
                ".", "..", "//",
                // Bad /modules paths.
                "/modules/",
                "/modules/.",
                "/modules//",
                "/modules/mod..name",
                "/modules/.mod.name",
                "/modules/mod.name.",
                // Missing /modules paths.
                "/modules/not.here",
                "/modules/mod.name/java/not/here",
                // Bad /packages paths.
                "/packages/",
                "/packages/.",
                "/packages//",
                "/packages/java..foo",
                "/packages/.java.foo",
                "/packages/java.foo.",
                // Missing /packages paths.
                "/packages/not.here",
                "/packages/java.foo/missing.link",
                // Extended non-directory paths.
                "/modules/mod.name/java/foo/First/xxx",
                "/packages/java.foo/mod.name/xxx");
        for (String path : badPaths) {
            assertFalse(image.get(path).isPresent(), "Bad path should not be present: " + path);
        }
    }

    // ---- Static test helper methods ----

    static void assertContent(String expected, Node node) {
        assertFalse(node.isDirectory());
        assertFalse(node.isLink());
        try {
            assertEquals(expected, new String(node.getContent(), UTF_8), "Unexpected node content");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Fake image implementation ----

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

        // ---- Test helper methods for common assertions ----

        /**
         * Asserts the given module node exists, and that this lookup triggers its creation.
         *
         * @param path module path string (must start with {@code "/module"}).
         */
        Node assertFirstLookup(String path) {
            ensureModulePath(path, "Can only assert first lookup for module resources");
            assertNotLogged(path);
            Node node = assertNode(path);
            assertLogged(path);
            return node;
        }

        /**
         * Asserts the given module node exists (not checking whether it existed before or not)..
         *
         * @param path module path string (must start with {@code "/module"}).
         */
        Node assertNode(String path) {
            Optional<Node> node = get(path);
            assertTrue(node.isPresent(), "Missing node: " + path);
            return node.get();
        }

        /**
         * Asserts that a given module node was previously created and cached.
         *
         * @param path module path string (must start with {@code "/module"}).
         */
        void assertLogged(String path) {
            ensureModulePath(path, "Can only assert log entries for module resources");
            assertTrue(
                    creationLog.contains(path),
                    "Expected logged path: " + path + "\nLogs:\n\t" + String.join("\n\t", creationLog));
        }

        /**
         * Asserts that a given module node was not previously created and cached.
         *
         * @param path module path string (must start with {@code "/module"}).
         */
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

        // ---- Populating test data ----

        // Fills the given map with files and directories implied by the given file list.
        private static void fillMap(Map<String, Boolean> map, Set<String> files) {
            assertTrue(files.stream().allMatch(p -> !p.isEmpty() && !p.startsWith("/") && !p.endsWith("/") && !p.contains("//")));
            files.stream().flatMap(TestImage::parentDirs).forEach(d -> map.put(d, true));
            files.forEach(f -> map.put(f, false));
        }

        // Streams the parent directories of the given path (e.g. "a/b/c" -> ["a/b", "a"]).
        private static Stream<String> parentDirs(String path) {
            return Stream.iterate(
                            path,
                            p -> !p.isEmpty(),
                            p -> p.substring(0, Math.max(p.lastIndexOf('/'), 0)))
                    .skip(1);
        }

        // Determines all possible module names based on the top level /modules/xxx directories.
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

        // Determines all possible package names based on /modules/*/xxx/yyy directories.
        private Set<String> getPackageNames() {
            Set<String> packageNames = new TreeSet<>();
            BiConsumer<String, Boolean> addPackageName = (p, isDir) -> {
                if (isDir) {
                    int sepIdx = p.indexOf('/');
                    if (sepIdx >= 0) {
                        // "mod/foo/bar" -> "foo.bar"
                        packageNames.add(p.substring(sepIdx + 1).replace('/', '.'));
                    }
                }
            };
            this.fileMap.forEach(addPackageName);
            this.previewMap.forEach(addPackageName);
            return packageNames;
        }

        // ---- Test image logic ----

        // Create a new resource node (in the "/modules" hierarchy) and log it.
        // Since this is the only place where callbacks to create/cache nodes are
        // made, we can be sure we log new nodes exactly when they are created.
        private Node logNewResource(String path, boolean isDir, boolean isPreview) {
            Node newNode = isDir
                    ? newResourceDirectory(path)
                    : newResource(path, () -> (path + (isPreview ? "*" : "")).getBytes(UTF_8));
            creationLog.add(newNode.toString());
            return newNode;
        }

        @Override
        protected Optional<Node> getResource(String resourcePath, boolean preview) {
            if (resourcePath.isEmpty()) {
                // "Root" directory always exists for path "".
                return Optional.of(logNewResource(resourcePath, true, preview));
            }
            Boolean isDir = (preview ? previewMap : fileMap).get(resourcePath);
            return isDir != null
                    ? Optional.of(logNewResource(resourcePath, isDir, preview))
                    : Optional.empty();
        }

        @Override
        protected void forEachChildOf(String dirPath, boolean preview, Consumer<Node> action) {
            // Resource root directory prefix is just "", not "/".
            String dirPrefix = dirPath.isEmpty() ? dirPath : (dirPath + "/");
            forEachChild(
                    dirPrefix,
                    preview,
                    (name, isDir) -> action.accept(logNewResource(dirPrefix + name, isDir, preview)));
        }

        @Override
        protected Set<String> getAllModuleNames() {
            return allModuleNames;
        }

        @Override
        protected Set<String> getAllPackageNames() {
            return allPackageNames;
        }

        // Process an action for each immediate child of the given directory prefix,
        // passing the child node's local "file name" and whether it is a directory.
        private void forEachChild(String dirPrefix, boolean preview, BiConsumer<String, Boolean> action) {
            int prefixLen = dirPrefix.length();
            Map<String, Boolean> map = preview ? previewMap : fileMap;
            for (var e : map.entrySet()) {
                String p = e.getKey();
                if (p.length() > prefixLen && p.startsWith(dirPrefix) && p.indexOf('/', prefixLen) == -1) {
                    action.accept(p.substring(prefixLen), e.getValue());
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
