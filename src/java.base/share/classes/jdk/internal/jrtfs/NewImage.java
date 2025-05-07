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
package jdk.internal.jrtfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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

@SuppressWarnings({"SimplifyOptionalCallChains"})
public abstract class NewImage {

    public static abstract class Node {
        final String path;
        // No need for volatile since values are private and idempotent.
        private String lazyCachedFileName = null;

        private Node(String path) {
            this.path = assertAbsolutePath(path);
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

        private String getFileName() {
            String name = lazyCachedFileName;
            if (name == null) {
                name = lazyCachedFileName = path.substring(path.lastIndexOf("/") + 1);
            }
            return name;
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

    public interface ContentSupplier {
        byte[] get() throws IOException;
    }

    private static final Comparator<Node> CHILD_NODE_ORDER = Comparator.comparing(Node::getFileName);
    private static final String ROOT = "";

    // Enum names are important, DO NOT CHANGE THEM!
    private enum TopLevelDir {
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

        String relativize(String absPath) {
            if (!isPrefixOf(absPath)) {
                throw new IllegalArgumentException("Bad path: '" + absPrefix + "' is not a prefix of '" + absPath + "'");
            }
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

    private final boolean isPreviewMode;

    private final ConcurrentMap<String, Node> nodeCache = new ConcurrentHashMap<>();

    protected NewImage(boolean isPreviewMode) {
        this.isPreviewMode = isPreviewMode;
    }

    public Optional<Node> get(String absPath) {
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
                    newDirectory(TopLevelDir.MODULES.toString(), () -> getChildResourceNodes(ROOT)),
                    newDirectory(TopLevelDir.PACKAGES.toString(), this::getPackageRoots)));
            return Optional.of(rootDir);
        }
        // "/modules" or "/packages"
        TopLevelDir dir = TopLevelDir.identify(absPath);
        if (dir == TopLevelDir.MODULES) {
            return getModulesNode(absPath);
        } else if (dir == TopLevelDir.PACKAGES) {
            return getPackagesNode(absPath);
        } else {
            return Optional.empty();
        }
    }

    protected abstract Optional<Node> getResource(String resourcePath, boolean preview);

    protected abstract void forEachChildOf(String resourcePath, boolean preview, Consumer<Node> action);

    protected abstract Set<String> getAllModuleNames();

    protected abstract Set<String> getAllPackageNames();

    protected Node newResource(String resourcePath, ContentSupplier contents) {
        return newFile(TopLevelDir.MODULES.resolve(resourcePath), contents);
    }

    protected Node newResourceDirectory(String resourcePath) {
        return newDirectory(TopLevelDir.MODULES.resolve(resourcePath), () -> getChildResourceNodes(resourcePath));
    }

    private List<Node> getChildResourceNodes(String resourcePath) {
        ArrayList<Node> nodes = new ArrayList<>();
        // Populate preview first since creating new nodes adds them to the cache.
        if (isPreviewMode) {
            forEachChildOf(resourcePath, true, nodes::add);
        }
        if (nodes.isEmpty()) {
            forEachChildOf(resourcePath, false, nodes::add);
        } else {
            // Only search in the subset of nodes populated by the initial scan.
            Node[] existingNodes = nodes.toArray(new Node[0]);
            Arrays.sort(existingNodes, CHILD_NODE_ORDER);
            forEachChildOf(resourcePath, false, child -> {
                if (Arrays.binarySearch(existingNodes, child, CHILD_NODE_ORDER) < 0) {
                    nodes.add(child);
                }
            });
        }
        return nodes;
    }

    private Optional<Node> getModulesNode(String absModulePath) {
        String resourcePath = TopLevelDir.MODULES.relativize(absModulePath);
        Optional<Node> value = Optional.empty();
        // Check preview first since creating new nodes adds them to the cache.
        if (isPreviewMode) {
            value = getResource(resourcePath, true);
        }
        if (!value.isPresent()) {
            value = getResource(resourcePath, false);
        }
        return value;
    }

    // Returns an individually specified node in the /packages hierarchy.
    private Optional<Node> getPackagesNode(String absPackagePath) {
        // /packages[/<java.package.name>[/<module.name>]]
        int pkgStart = absPackagePath.indexOf('/', 1) + 1;
        if (pkgStart == 0) {
            // /packages
            return Optional.of(newDirectory(TopLevelDir.PACKAGES.toString(), this::getPackageRoots));
        }
        String packageName = absPackagePath.substring(pkgStart);
        int sepIdx = packageName.indexOf('/');
        if (sepIdx == -1) {
            if (isValidPackageOrModuleName(packageName)) {
                // /packages/<java.package.name>
                return getPackageDirectory(packageName);
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
        return getAllPackageNames().stream()
                .map(this::getPackageDirectory)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    // /packages/<java.package.name>
    private Optional<Node> getPackageDirectory(String packageName) {
        // WARNING: Currently we *assume* the subclass will give us valid module names!
        if (getAllModuleNames().stream()
                .anyMatch(moduleName -> getPackageLink(packageName, moduleName).isPresent())) {
            String absPath = TopLevelDir.PACKAGES.resolve(packageName);
            return Optional.of(newDirectory(absPath, () -> getPackageChildLinks(packageName)));
        }
        return Optional.empty();
    }

    // /packages/<java.package.name>/<module.name>
    private List<Node> getPackageChildLinks(String packageName) {
        return getAllModuleNames().stream()
                .map(moduleName -> getPackageLink(packageName, moduleName))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    // /packages/<java.package.name>/<module.name>
    private Optional<Node> getPackageLink(String packageName, String moduleName) {
        String absModulePath = TopLevelDir.MODULES.resolve(moduleName);
        // Test for implied package directory "/modules/module.name/java/package/name"
        if (get(absModulePath + "/" + packageName.replace('.', '/')).map(Node::isDirectory).orElse(false)) {
            // Since the target is a parent of the package directory, it MUST exist.
            Node targetDir = get(absModulePath).get();
            String absLinkPath = TopLevelDir.PACKAGES.resolve(packageName + "/" + moduleName);
            return Optional.of(newLink(absLinkPath, targetDir));
        }
        return Optional.empty();
    }

    private Node newDirectory(String absPath, Supplier<List<Node>> getChildren) {
        return nodeCache.computeIfAbsent(absPath, p -> new DirectoryNode(p, getChildren));
    }

    private static class DirectoryNode extends Node {
        private final LazyChildList children;

        private DirectoryNode(String path, Supplier<List<Node>> childNodes) {
            super(path);
            this.children = LazyChildList.of(childNodes);
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

    private Node newLink(String path, Node target) {
        return nodeCache.computeIfAbsent(path, p -> new LinkNode(p, target));
    }

    private static class LinkNode extends Node {
        private final Node target;

        private LinkNode(String path, Node target) {
            super(path);
            this.target = requireNonNull(target);
        }

        @Override
        public boolean isLink() {
            return true;
        }

        @Override
        public Node resolveLink(boolean recursive) {
            return recursive ? target.resolveLink(true) : target;
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
    // Visible for whitebox testing.
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
    private static class LazyChildList implements Supplier<List<Node>> {
        private volatile Supplier<List<Node>> source;
        private volatile List<Node> childList = null;

        static LazyChildList of(Supplier<List<Node>> source) {
            return new LazyChildList(source);
        }

        private LazyChildList(Supplier<List<Node>> source) {
            requireNonNull(source);
            this.source = () -> {
                List<Node> children = source.get();
                children.sort(CHILD_NODE_ORDER);
                return Collections.unmodifiableList(children);
            };
        }

        @Override
        public List<Node> get() {
            List<Node> list = this.childList;
            if (list == null) {
                Supplier<List<Node>> source = this.source;
                if (source != null) {
                    list = this.childList = source.get();
                    this.source = null;
                } else {
                    // Race: If source is null, value must have been,
                    // written, so a non-null value can *now* be read.
                    list = requireNonNull(this.childList);
                }
            }
            return list;
        }
    }
}
