/*
 * Copyright (c) 2015, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.jrtfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static jdk.internal.jrtfs.NewImage.TopLevelDir.MODULES;
import static jdk.internal.jrtfs.NewImage.TopLevelDir.PACKAGES;

@SuppressWarnings({"SimplifyOptionalCallChains"})
public final class NewImage {

    public static abstract class Node {
        final String path;
        final int nameIdx;

        private Node(String path) {
            this.path = assertAbsolutePath(path);
            this.nameIdx = path.lastIndexOf('/') + 1;
        }

        public boolean isDirectory() {
            return false;
        }

        public boolean isLink() {
            return false;
        }

        public List<Node> getChildren() {
            throw new IllegalArgumentException("not a directory: " + path);
        }

        public byte[] getContent() throws IOException {
            throw new IllegalArgumentException("not a resource: " + path);
        }

        public Node resolveLink(boolean recursive) {
            return this;
        }

        private int compareFileName(Node other) {
            String otherPath = other.path;
            assert nameIdx == other.nameIdx && path.regionMatches(0, otherPath, 0, nameIdx);
            int len = Math.min(path.length(), otherPath.length());
            for (int i = nameIdx; i < len; i++) {
                int diff = ((int) path.charAt(i)) - ((int) otherPath.charAt(i));
                if (diff == 0) {
                    continue;
                }
                return Integer.signum(diff);
            }
            return Integer.compare(path.length(), otherPath.length());
        }

        @Override
        public final boolean equals(Object obj) {
            return obj instanceof Node && path.equals(((Node) obj).path);
        }

        @Override
        public final int hashCode() {
            return path.hashCode();
        }

        @Override
        public final String toString() {
            return path;
        }
    }

    public interface ResourceProvider {
        Optional<Node> getResource(String resourcePath, NodeFactory nodeFactory, boolean isPreview);

        void forEachChildOf(NodeFactory nodeFactory, String resourcePath, boolean isPreview, Consumer<Node> action);

        /// Returns the complete set of module names available from the provider,
        /// including modules for which only preview resources exist. This does
        /// not depend on preview mode because there's no concept of a completely
        /// empty module.
        Set<String> getAllModuleNames();

        /// Returns the set of (dot-separated) package names available across
        /// all resources.
        ///
        /// This is a potentially expensive operation involving scanning many
        /// resource paths, so the result is cached and this method will only
        /// be called once for each image instance.
        Set<String> getPackageNames(boolean withPreviewPackages);

        /// Test whether a specific package exists (this may be called often, so
        /// should be efficient).
        boolean packageExists(String moduleName, String packageName, boolean withPreviewPackages);

        /// Returns the set of module names in which the given package exists.
        ///
        /// Packages may appear in more than one module by virtue of modules
        /// containing "sub packages".
        Set<String> getModulesForPackage(String packageName, boolean withPreviewPackages);
    }

    public class NodeFactory {
        public Node newResource(String resourcePath, ContentSupplier contents) {
            return newFile(MODULES.resolve(resourcePath), contents);
        }

        public Node newResourceDirectory(String resourcePath) {
            return newDirectory(MODULES.resolve(resourcePath), () -> getChildResourceNodes(resourcePath));
        }

        private NodeFactory() {}
    }

    public interface ContentSupplier {
        byte[] get() throws IOException;
    }

    private static final String ROOT = "";

    // Enum names are important, DO NOT CHANGE THEM!
    enum TopLevelDir {
        MODULES,
        PACKAGES;

        private final String absPrefix = assertAbsolutePath("/" + name().toLowerCase(Locale.ROOT));

        static TopLevelDir identify(String path) {
            return MODULES.isPrefixOf(path) ? MODULES
                    : PACKAGES.isPrefixOf(path) ? PACKAGES
                    : null;
        }

        String resolve(String relPath) {
            return relPath.isEmpty() ? absPrefix : absPrefix + "/" + checkRelativePath(relPath);
        }

        // Requires that the path is within the top-level directory.
        String relativize(String absPath) {
            assert isPrefixOf(absPath) : "Bad path: '" + absPrefix + "' is not a prefix of '" + absPath + "'";
            return absPath.length() == absPrefix.length() ? "" : absPath.substring(absPrefix.length() + 1);
        }

        private boolean isPrefixOf(String path) {
            int len = absPrefix.length();
            return path.startsWith(absPrefix) && (path.length() == len || path.charAt(len) == '/');
        }

        @Override
        public String toString() {
            return absPrefix;
        }
    }

    private final ResourceProvider provider;
    private final boolean isPreviewMode;
    private final NodeFactory factory = new NodeFactory();
    private final ConcurrentMap<String, Node> nodeCache = new ConcurrentHashMap<>();
    private final Memoized<Set<String>> lazyModuleNames;
    private final Memoized<Set<String>> lazyPackageNames;

    public NewImage(ResourceProvider provider, boolean isPreviewMode) {
        this.provider = requireNonNull(provider);
        this.isPreviewMode = isPreviewMode;
        this.lazyModuleNames = Memoized.of(
                () -> Collections.unmodifiableSet(provider.getAllModuleNames()));
        this.lazyPackageNames = Memoized.of(
                () -> Collections.unmodifiableSet(provider.getPackageNames(isPreviewMode)));
    }

    public Optional<Node> findNode(String absPath) {
        // Given path is permitted to be any string, so don't throw here.
        if (!isAbsolutePath(absPath)) {
            return Optional.empty();
        }
        Node node = nodeCache.get(absPath);
        if (node != null) {
            return Optional.of(node);
        }
        if (ROOT.equals(absPath)) {
            Node rootDir = newDirectory(ROOT, () -> Arrays.asList(
                    // ROOT here is the root of the resource hierarchy *under* /modules.
                    newDirectory(MODULES.toString(), () -> getChildResourceNodes(ROOT)),
                    newDirectory(PACKAGES.toString(), this::getPackageRoots)));
            return Optional.of(rootDir);
        }
        // "/modules" or "/packages"
        TopLevelDir dir = TopLevelDir.identify(absPath);
        if (dir == MODULES) {
            return getModulesNode(MODULES.relativize(absPath));
        } else if (dir == PACKAGES) {
            return getPackagesNode(absPath);
        } else {
            return Optional.empty();
        }
    }

    private List<Node> getChildResourceNodes(String resourcePath) {
        ArrayList<Node> nodes = new ArrayList<>();
        // Populate preview first since creating new nodes adds them to the cache.
        if (isPreviewMode) {
            provider.forEachChildOf(factory, resourcePath, true, nodes::add);
        }
        if (nodes.isEmpty()) {
            provider.forEachChildOf(factory, resourcePath, false, nodes::add);
        } else {
            // Only search in the subset of nodes populated by the initial scan.
            Node[] existingNodes = nodes.toArray(new Node[0]);
            Arrays.sort(existingNodes, Node::compareFileName);
            provider.forEachChildOf(factory, resourcePath, false, child -> {
                if (Arrays.binarySearch(existingNodes, child, Node::compareFileName) < 0) {
                    nodes.add(child);
                }
            });
        }
        return nodes;
    }

    private Optional<Node> getModulesNode(String resourcePath) {
        Optional<Node> value = Optional.empty();
        // Check preview first since creating new nodes adds them to the cache.
        if (isPreviewMode) {
            value = provider.getResource(resourcePath, factory, true);
        }
        if (!value.isPresent()) {
            value = provider.getResource(resourcePath, factory, false);
        }
        return value;
    }

    // Returns an individually specified node in the /packages hierarchy.
    private Optional<Node> getPackagesNode(String absPackagePath) {
        // /packages[/<java.package.name>[/<module.name>]]
        int pkgStart = absPackagePath.indexOf('/', 1) + 1;
        if (pkgStart == 0) {
            // /packages
            return Optional.of(newDirectory(PACKAGES.toString(), this::getPackageRoots));
        }
        String packageName = absPackagePath.substring(pkgStart);
        int sepIdx = packageName.indexOf('/');
        if (sepIdx == -1) {
            if (isValidPackageOrModuleName(packageName)) {
                // /packages/<java.package.name>
                return findPackageDirectory(packageName);
            }
        } else {
            String moduleName = packageName.substring(sepIdx + 1);
            packageName = packageName.substring(0, sepIdx);
            if (isValidPackageOrModuleName(moduleName) && isValidPackageOrModuleName(packageName)) {
                // /packages/<java.package.name>/<module.name>
                return getPackageLink(packageName, moduleName);
            }
        }
        return Optional.empty();
    }

    // Returns the child nodes of the /packages directory.
    private List<Node> getPackageRoots() {
        // WARNING: Currently we *assume* the subclass will give us valid package names!
        return lazyPackageNames.get().stream()
                .map(this::newPackageDirectory)
                .collect(toList());
    }

    // /packages/<java.package.name>
    private Optional<Node> findPackageDirectory(String packageName) {
        return lazyPackageNames.get().contains(packageName)
                ? Optional.of(newPackageDirectory(packageName))
                : Optional.empty();
    }

    // Call MUST have verified this is the name of a package that exists.
    private Node newPackageDirectory(String packageName) {
        String absPath = PACKAGES.resolve(packageName);
        return newDirectory(absPath, () -> getPackageChildLinks(packageName));
    }

    // /packages/<java.package.name>/<module.name>
    private List<Node> getPackageChildLinks(String packageName) {
        return provider.getModulesForPackage(packageName, isPreviewMode)
                .stream()
                .peek(this::assertModuleName)
                // Since it's a value module name, the modules node must exist.
                .map(mod -> newLink(PACKAGES.resolve(packageName + "/" + mod), mod))
                .collect(toList());
    }

    void assertModuleName(String moduleName) {
        assert lazyModuleNames.get().contains(moduleName) : "Invalid module name: " + moduleName;
    }

    // /packages/<java.package.name>/<module.name>
    private Optional<Node> getPackageLink(String packageName, String moduleName) {
        if (provider.packageExists(moduleName, packageName, isPreviewMode)) {
            // Since the target is a parent of the package directory, it MUST exist.
            String absLinkPath = PACKAGES.resolve(packageName + "/" + moduleName);
            return Optional.of(newLink(absLinkPath, moduleName));
        }
        return Optional.empty();
    }

    private Node newDirectory(String absPath, Supplier<List<Node>> getChildren) {
        return nodeCache.computeIfAbsent(absPath, p -> new DirectoryNode(p, getChildren));
    }

    private static class DirectoryNode extends Node {
        private final Memoized<List<Node>> children;

        private DirectoryNode(String path, Supplier<List<Node>> childNodes) {
            super(path);
            this.children = Memoized.of(() -> {
                List<Node> nodes = childNodes.get();
                // Note: Sorting here will ensure that directory entries are consistently
                // sorted, but in rough benchmarks it adds a slight delay when walking
                // though the "file tree" for the first time. If removed, then preview
                // entries will appear "out of order" with non-preview entires.
                nodes.sort(Node::compareFileName);
                return Collections.unmodifiableList(nodes);
            });
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        @Override
        public List<Node> getChildren() {
            return children.get();
        }
    }

    private Node newFile(String path, ContentSupplier contents) {
        return nodeCache.computeIfAbsent(path, p -> new FileNode(p, contents));
    }

    private static class FileNode extends Node {
        private final ContentSupplier contents;

        private FileNode(String path, ContentSupplier contents) {
            super(path);
            this.contents = requireNonNull(contents);
        }

        @Override
        public byte[] getContent() throws IOException {
            return this.contents.get();
        }
    }

    private Node newLink(String path, String linkModuleName) {
        return nodeCache.computeIfAbsent(path, p -> new LinkNode(p, linkModuleName));
    }

    private class LinkNode extends Node {
        private final Memoized<Node> target;

        private LinkNode(String path, String linkModuleName) {
            super(path);
            this.target = Memoized.of(() -> getModulesNode(linkModuleName).get());
        }

        @Override
        public boolean isLink() {
            return true;
        }

        @Override
        public Node resolveLink(boolean recursive) {
            Node node = target.get();
            return recursive ? node.resolveLink(true) : node;
        }
    }

    // WARNING: This needs to be properly updated according to the spec.
    // Visible for whitebox testing.
    static boolean isValidPackageOrModuleName(String name) {
        return !name.isEmpty()
                && !name.startsWith(".")
                && !name.endsWith(".")
                && !name.contains("..")
                && !name.contains("/");
    }

    // WARNING: This needs to be properly updated according to the spec.
    // Visible for white-box testing.
    static boolean isValidPath(String path, boolean isAbsolute) {
        if (path.isEmpty()) {
            // Empty is NOT a valid relative path, so we can always do (abs + "/" + rel).
            return isAbsolute;
        }
        if (isAbsolute && path.charAt(0) != '/') {
            return false;
        }
        // Start index for new segment, prohibit . at start of segment.
        int startIdx = isAbsolute ? 1 : 0;
        boolean allowSlash = false;
        boolean allowDot = false;
        for (int i = startIdx; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!allowSlash) return false;
                // Same setup as at start (start of new segment).
                allowSlash = false;
                allowDot = false;
                continue;
            } else if (c == '.') {
                if (!allowDot) return false;
                // Unlike above, allow "./" for trailing dots in names.
                allowDot = false;
                continue;
            }
            allowSlash = true;
            allowDot = true;
        }
        return allowSlash;
    }

    // For places where the data should definitely already be correct.
    private static String assertAbsolutePath(String path) {
        assert isValidPath(path, true) : "Invalid absolute path: " + path;
        return path;
    }

    private static boolean isAbsolutePath(String path) {
        return isValidPath(path, true);
    }

    private static String checkRelativePath(String path) {
        if (isValidPath(path, false)) {
            return path;
        }
        throw new IllegalArgumentException("Invalid relative path: " + path);
    }

    /// A lock-free, immutable, memoized child list for directory nodes.
    ///
    /// The only "racy" behaviour here is that the supplier may be called several
    /// times, and thus cause `getChildren()` to return different `List` instances.
    /// Since node identity is controlled by a `ConcurrentMap`, the child nodes in
    /// the lists should be the same instances and in the same order.
    ///
    /// Providing that subclass implementations only calculate the minimal set of
    /// nodes required to satisfy the child list, there can never be a risk of
    /// reentrant processing (due to the acyclic nature of a node hierarchy).
    private static class Memoized<T> implements Supplier<T> {
        private volatile Supplier<T> source;
        private volatile T value = null;

        static <T> Memoized<T> of(Supplier<T> source) {
            return new Memoized<T>(source);
        }

        private Memoized(Supplier<T> source) {
            this.source = requireNonNull(source);
        }

        @Override
        public T get() {
            T v = this.value;
            if (v == null) {
                Supplier<T> source = this.source;
                if (source != null) {
                    v = this.value = source.get();
                    this.source = null;
                } else {
                    // Race: If source is null, value must have been,
                    // written, so a non-null value can *now* be read.
                    v = requireNonNull(this.value);
                }
            }
            return v;
        }
    }
}
