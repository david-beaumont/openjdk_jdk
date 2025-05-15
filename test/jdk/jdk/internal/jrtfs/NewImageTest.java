/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
import jdk.internal.jrtfs.NewImage.NodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Boolean.TRUE;
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
    public void basicLazyNodeCreation() {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.one/java/foo/Foo.class",
                        "mod.two/java/bar/Bar.class"),
                Set.of());
        NewImage image = new NewImage(provider, false);

        // Normal file lookup does not create the containing directory.
        Node node = provider.assertFirstLookup(image, "/modules/mod.one/java/foo/Foo.class");

        assertFalse(node.isDirectory(), "Node: " + node);
        assertContent("mod.one/java/foo/Foo.class", node);
        provider.assertNotLogged("/modules/mod.one/java/foo");

        // Normal directory lookup does not create child nodes.
        provider.assertNotLogged("/modules/mod.two");
        Node modLink = assertNode(image, "/packages/java.bar/mod.two");
        // Making a package link does NOT create the linked directory
        Node modTwo = provider.assertFirstLookup(image, "/modules/mod.two");
        // Package links resolve to the linked node.
        assertEquals(modTwo, modLink.resolveLink(false));

        // The root /modules directory is created only when requested.
        Node modOne = provider.assertFirstLookup(image, "/modules/mod.one");
        Node modRoot = provider.assertFirstLookup(image, "/modules");
        assertEquals(asList(modOne, modTwo), modRoot.getChildren());
    }

    @Test
    public void previewFileReplace() {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.name/java/foo/First",
                        "mod.name/java/foo/Second",
                        "mod.name/java/foo/Third"),
                Set.of(
                        "mod.name/java/foo/Second"));
        NewImage image = new NewImage(provider, true);

        // There are 3 files in the directory.
        Node first = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/First");
        Node second = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/Second");
        Node third = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/Third");
        Node dir = provider.assertFirstLookup(image, "/modules/mod.name/java/foo");
        assertTrue(dir.isDirectory());
        assertEquals(asList(first, second, third), dir.getChildren());

        // But one of them comes from the preview set (the trailing '*').
        assertContent("mod.name/java/foo/First", first);
        assertContent("mod.name/java/foo/Second*", second);
        assertContent("mod.name/java/foo/Third", third);

        // This node exists without replaced content in no-preview image.
        NewImage noPreview = new NewImage(provider, false);
        assertContent("mod.name/java/foo/Second",
                assertNode(noPreview, "/modules/mod.name/java/foo/Second"));
    }

    @Test
    public void previewFileAddition() {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.name/java/foo/First",
                        "mod.name/java/foo/Third"),
                Set.of(
                        "mod.name/java/foo/Second",
                        "mod.name/java/foo/Xtra"));
        NewImage image = new NewImage(provider, true);

        // There are 4 files in the directory.
        Node first = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/First");
        Node second = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/Second");
        Node third = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/Third");
        Node last = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/Xtra");
        Node dir = provider.assertFirstLookup(image, "/modules/mod.name/java/foo");
        assertTrue(dir.isDirectory());
        assertEquals(asList(first, second, third, last), dir.getChildren());

        // Two are from the preview set (the trailing '*').
        assertContent("mod.name/java/foo/First", first);
        assertContent("mod.name/java/foo/Second*", second);
        assertContent("mod.name/java/foo/Third", third);
        assertContent("mod.name/java/foo/Xtra*", last);

        // The preview nodes are missing in the non-preview image.
        NewImage noPreview = new NewImage(provider, false);
        assertTrue(noPreview.findNode("/modules/mod.name/java/foo/First").isPresent());
        assertFalse(noPreview.findNode("/modules/mod.name/java/foo/Second").isPresent());
        assertTrue(noPreview.findNode("/modules/mod.name/java/foo/Third").isPresent());
        assertFalse(noPreview.findNode("/modules/mod.name/java/foo/Xtra").isPresent());
    }

    @Test
    public void previewDirectoryAddition() {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.name/java/foo/First",
                        "mod.name/java/foo/Second"),
                Set.of(
                        "mod.name/java/foo/bar/SubDirFile",
                        "mod.name/java/gus/OtherDirFile"));
        NewImage image = new NewImage(provider, true);

        // A new directory was added from the preview set.
        Node first = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/First");
        Node second = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/Second");
        Node subDir = provider.assertFirstLookup(image, "/modules/mod.name/java/foo/bar");
        assertTrue(subDir.isDirectory());
        Node dir = provider.assertFirstLookup(image, "/modules/mod.name/java/foo");
        assertEquals(asList(first, second, subDir), dir.getChildren());

        // Preview files may create entirely new directories.
        provider.assertFirstLookup(image, "/modules/mod.name/java/foo/bar/SubDirFile");
        provider.assertFirstLookup(image, "/modules/mod.name/java/gus/OtherDirFile");
        // And new packages inferred by them are reflected correctly in /packages
        assertNode(image, "/packages/java.gus/mod.name");

        // None of this appears in the non-preview image.
        NewImage noPreview = new NewImage(provider, false);
        assertFalse(noPreview.findNode("/modules/mod.name/java/foo/bar").isPresent());
        assertFalse(noPreview.findNode("/modules/mod.name/java/gus").isPresent());
        assertFalse(noPreview.findNode("/packages/java.gus/mod.name").isPresent());
    }

    @Test
    public void topLevelNonDirectory() {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.name/java/foo/First",
                        "not.a.directory",
                        "mod.name/java/foo/Second"),
                Set.of(
                        "normal.file",
                        "mod.name/java/bar/Other"));
        NewImage image = new NewImage(provider, true);

        // Top level non-directory files can exist (they probably shouldn't IRL),
        // but they are not implied to be module names.
        assertFalse(provider.assertFirstLookup(image, "/modules/not.a.directory").isDirectory());
        assertFalse(provider.assertFirstLookup(image, "/modules/normal.file").isDirectory());

        Node packages = assertNode(image, "/packages");
        Node pkgJava = assertNode(image, "/packages/java");
        Node pkgFoo = assertNode(image, "/packages/java.foo");
        Node pkgBar = assertNode(image, "/packages/java.bar");
        assertEquals(asList(pkgJava, pkgBar, pkgFoo), packages.getChildren());
    }

    @Test
    public void packageLinks() {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.one/java/foo/Foo.class",
                        "mod.two/java/bar/Bar.class"),
                Set.of(
                        "mod.preview/java/foo/preview/Preview.class"));
        NewImage image = new NewImage(provider, true);
        Node pkgDir = assertNode(image, "/packages/java.foo");
        assertTrue(pkgDir.isDirectory());
        List<Node> children = pkgDir.getChildren();
        assertTrue(children.stream().allMatch(Node::isLink), "Package directory should contain only links");
        assertEquals(
                Set.of(assertNode(image, "/modules/mod.one"), assertNode(image, "/modules/mod.preview")),
                children.stream().map(n -> n.resolveLink(false)).collect(Collectors.toSet()));


    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "/modules",
            "/modules/mod.name",
            "/modules/mod.name/java",
            "/modules/mod.name/java/foo",
            "/modules/mod.name/java/foo/First",
            "/packages",
            "/packages/java.foo",
            "/packages/java.foo/mod.name"})
    public void goodPaths(String path) {
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.name/java/foo/First",
                        "mod.name/java/foo/Second"),
                Set.of());
        NewImage image = new NewImage(provider, false);
        assertTrue(image.findNode(path).isPresent(), "Good path should be present: " + path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
            "/packages/java.foo/mod.name/xxx"})
    public void badPaths(String path) {
        // Same provider and image as above.
        TestProvider provider = new TestProvider(
                Set.of(
                        "mod.name/java/foo/First",
                        "mod.name/java/foo/Second"),
                Set.of());
        NewImage image = new NewImage(provider, false);
        assertFalse(image.findNode(path).isPresent(), "Bad path should not be present: " + path);
    }

    // ---- Static test helper methods ----

    /**
     * Asserts the given module node exists (not checking whether it existed before or not).
     *
     * @param path module path string (must start with {@code "/module"}).
     */
    static Node assertNode(NewImage image, String path) {
        Optional<Node> node = image.findNode(path);
        assertTrue(node.isPresent(), "Missing node: " + path);
        return node.get();
    }

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

    static class TestProvider implements NewImage.ResourceProvider {
        private final Map<String, Boolean> previewMap = new LinkedHashMap<>();
        private final Map<String, Boolean> fileMap = new LinkedHashMap<>();
        private final Set<String> allModuleNames;
        // Log of created resources (by path string). Useful to demonstrate lazy creation.
        private final Set<String> creationLog = new LinkedHashSet<>();

        TestProvider(Set<String> resourceFiles, Set<String> previewFiles) {
            fillMap(this.fileMap, resourceFiles);
            fillMap(this.previewMap, previewFiles);
            allModuleNames = Collections.unmodifiableSet(findAllModuleNames());
        }

        // ---- Test helper methods for common assertions ----

        /**
         * Asserts the given module node exists, and that this lookup triggers its creation.
         *
         * @param path module path string (must start with {@code "/module"}).
         */
        Node assertFirstLookup(NewImage image, String path) {
            ensureModulePath(path, "Can only assert first lookup for module resources");
            assertNotLogged(path);
            Node node = assertNode(image, path);
            assertLogged(path);
            return node;
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
            files.stream().flatMap(TestProvider::parentDirs).forEach(d -> map.put(d, true));
            files.forEach(f -> map.put(f, false));
        }

        private Set<String> findAllModuleNames() {
            Set<String> moduleNames = new TreeSet<>();
            BiConsumer<String, Boolean> addModuleName = (p, isDir) -> {
                if (isDir && !p.contains("/")) {
                    moduleNames.add(p);
                }
            };
            // In the real image, the module name encloses any preview files, so
            // the total set of module names *always* includes preview-only modules.
            this.fileMap.forEach(addModuleName);
            this.previewMap.forEach(addModuleName);
            return moduleNames;
        }

        // Streams the parent directories of the given path (e.g. "a/b/c" -> ["a/b", "a"]).
        private static Stream<String> parentDirs(String path) {
            return Stream.iterate(
                            path,
                            p -> !p.isEmpty(),
                            p -> p.substring(0, Math.max(p.lastIndexOf('/'), 0)))
                    .skip(1);
        }

        // ---- Test image logic ----

        // Create a new resource node (in the "/modules" hierarchy) and log it.
        // Since this is the only place where callbacks to create/cache nodes are
        // made, we can be sure we log new nodes exactly when they are created.
        private Node logNewResource(NodeFactory factory, String path, boolean isDir, boolean isPreview) {
            Node newNode = isDir
                    ? factory.newResourceDirectory(path)
                    : factory.newResource(path, () -> (path + (isPreview ? "*" : "")).getBytes(UTF_8));
            creationLog.add(newNode.toString());
            return newNode;
        }

        @Override
        public Optional<Node> getResource(String resourcePath, NodeFactory factory, boolean isPreview) {
            if (resourcePath.isEmpty()) {
                // "Root" directory always exists for path "".
                return Optional.of(logNewResource(factory, resourcePath, true, isPreview));
            }
            Boolean isDir = (isPreview ? previewMap : fileMap).get(resourcePath);
            return isDir != null
                    ? Optional.of(logNewResource(factory, resourcePath, isDir, isPreview))
                    : Optional.empty();
        }

        @Override
        public void forEachChildOf(NodeFactory factory, String dirPath, boolean isPreview, Consumer<Node> action) {
            // Resource root directory prefix is just "", not "/".
            String dirPrefix = dirPath.isEmpty() ? dirPath : (dirPath + "/");
            forEachChild(
                    dirPrefix,
                    isPreview,
                    (name, isDir) -> action.accept(logNewResource(factory, dirPrefix + name, isDir, isPreview)));
        }

        public boolean packageExists(String moduleName, String packageName, boolean withPreviewPackages) {
            String modPath = moduleName + "/" + packageName.replace('.', '/');
            return fileMap.get(modPath) == TRUE
                    || (withPreviewPackages && previewMap.get(modPath) == TRUE);
        }

        @Override
        public Set<String> getModulesForPackage(String packageName, boolean withPreviewNames) {
            Set<String> modules = new TreeSet<>();
            String packagePath = "/" + packageName.replace('.', '/');
            allModuleNames.forEach(moduleName -> {
                String modPath = moduleName + packagePath;
                if (fileMap.get(modPath) == TRUE
                        || (withPreviewNames && previewMap.get(modPath) == TRUE)) {
                    modules.add(moduleName);
                }
            });
            return modules;
        }

        @Override
        public Set<String> getAllModuleNames() {
            return allModuleNames;
        }

        @Override
        public Set<String> getPackageNames(boolean withPreviewNames) {
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
            if (withPreviewNames) {
                this.previewMap.forEach(addPackageName);
            }
            return packageNames;
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
            return String.format("Files: %s\nPreview: %s\nLog: %s", fileMap, previewMap, creationLog);
        }
    }
}
