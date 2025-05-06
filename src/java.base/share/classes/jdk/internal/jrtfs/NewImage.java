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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

@SuppressWarnings({"EnhancedSwitchMigration", "SimplifyOptionalCallChains"})
public abstract class NewImage {

    public static abstract class Node {
        final JrtPath path;

        private Node(JrtPath path) {
            this.path = path;
        }

        public boolean isDirectory() {
            return false;
        }

        public boolean isLink() {
            return false;
        }

        public List<Node> getChildren() {
            throw new IllegalStateException("Not a directory: " + path);
        }

        public byte[] loadResource() {
            throw new IllegalStateException("Not a resource: " + path);
        }

        public Node resolve() {
            return this;
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
            return path.toString();
        }
    }

    private static final JrtFileSystem JRTFS;
    static {
        try {
            JRTFS = new JrtFileSystem(null, emptyMap());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final JrtPath ROOT = JRTFS.getRootPath();
    private static final JrtPath MODULES = JRTFS.getRootPath().resolve("modules");
    private static final JrtPath PACKAGES = JRTFS.getRootPath().resolve("packages");
    private static final Path MODULES_ROOT = MODULES.relativize(MODULES);

    // Only compare child nodes of the same parent, so comparison by file name is okay.
    private static final Comparator<Node> CHILD_NODE_ORDER = Comparator.comparing(n -> n.path.getFileName());

    private final boolean isPreviewMode;

    private final ConcurrentMap<JrtPath, Node> nodeCache = new ConcurrentHashMap<>();

    protected NewImage(boolean isPreviewMode) {
        this.isPreviewMode = isPreviewMode;
    }

    public Optional<Node> get(String path) {
        return get(ROOT.resolve(path));
    }

    Optional<Node> get(JrtPath jrtPath) {
        assert jrtPath.isAbsolute();

        Node node = nodeCache.get(jrtPath);
        if (node != null) {
            return Optional.of(node);
        }
        if (ROOT.equals(jrtPath)) {
            Node rootDir = newDirectory(ROOT, () -> Arrays.asList(
                    newDirectory(MODULES, () -> getChildResourceNodes(MODULES_ROOT)),
                    newDirectory(PACKAGES, this::getPackageRoots)));
            return Optional.of(rootDir);
        }
        // "/modules" or "/packages"
        if (jrtPath.startsWith(MODULES)) {
            return getModulesNode(jrtPath);
        } else if (jrtPath.startsWith(PACKAGES)) {
            return getPackagesNode(jrtPath);
        } else {
            return Optional.empty();
        }
    }

    protected abstract Optional<Node> getResource(Path resourcePath, boolean preview);

    protected abstract void forEachChildOf(Path resourcePath, boolean preview, Consumer<Node> action);

    protected abstract Set<String> getAllModuleNames();

    protected abstract Set<String> getAllPackageNames();

    protected Node newResource(Path resourcePath, Supplier<byte[]> contents) {
        return newFile(MODULES.resolve(resourcePath), contents);
    }

    protected Node newResourceDirectory(Path resourcePath) {
        return newDirectory(MODULES.resolve(resourcePath), () -> getChildResourceNodes(resourcePath));
    }

    private List<Node> getChildResourceNodes(Path resourcePath) {
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
        nodes.sort(CHILD_NODE_ORDER);
        return nodes;
    }

    private Optional<Node> getModulesNode(JrtPath path) {
        Path resourcePath = MODULES.relativize(path);
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
    private Optional<Node> getPackagesNode(JrtPath path) {
        // /packages/<java.package.name>[/<module.name>]
        switch (path.getNameCount()) {
            case 1:
                // /packages
                return Optional.of(newDirectory(PACKAGES, this::getPackageRoots));
            case 2:
                // /packages/<java.package.name>
                return getPackageDirectory(path);
            case 3:
                // /packages/<java.package.name>/<module.name>
                return getPackageLink(path);
            default:
                return Optional.empty();
        }
    }

    // Returns the child nodes of the /packages directory.
    private List<Node> getPackageRoots() {
        return getAllPackageNames().stream()
                .map(PACKAGES::resolve)
                .map(this::getPackageDirectory)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    // /packages/<java.package.name>
    private Optional<Node> getPackageDirectory(JrtPath jrtPath) {
        boolean packageExists = getAllModuleNames().stream()
                .anyMatch(name -> getPackageLink(jrtPath.resolve(name)).isPresent());
        return packageExists
                ? Optional.of(newDirectory(jrtPath, () -> getPackageChildLinks(jrtPath)))
                : Optional.empty();
    }

    // /packages/<java.package.name>/<module.name>
    private List<Node> getPackageChildLinks(JrtPath jrtPath) {
        return getAllModuleNames().stream()
                .map(name -> getPackageLink(PACKAGES.resolve(name)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    // /packages/<java.package.name>/<module.name>
    private Optional<Node> getPackageLink(JrtPath jrtPath) {
        String packageName = jrtPath.getName(1).toString();
        String moduleName = jrtPath.getName(2).toString();
        return get(MODULES.resolve(moduleName).resolve(packageName.replace('.', '/')))
                .filter(Node::isDirectory)
                // Sub-paths are always relative even if they come from an absolute path.
                // There is nothing like "trimToLength(2)" or similar.
                .map(t -> get(t.path.subpath(0, 2).toAbsolutePath()).get())
                .map(t -> newLink(jrtPath, t));
    }

    private Node newDirectory(JrtPath path, Supplier<List<Node>> getChildren) {
        return nodeCache.computeIfAbsent(path, p -> new DirectoryNode(p, getChildren));
    }

    private static class DirectoryNode extends Node {
        private final Memoized<List<Node>> children;

        private DirectoryNode(JrtPath path, Supplier<List<Node>> childNodes) {
            super(path);
            this.children = Memoized.of(() -> Collections.unmodifiableList(childNodes.get()));
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

    private Node newFile(JrtPath path, Supplier<byte[]> contents) {
        return nodeCache.computeIfAbsent(path, p -> new FileNode(p, contents));
    }

    private static class FileNode extends Node {
        private final Supplier<byte[]> contents;

        private FileNode(JrtPath path, Supplier<byte[]> contents) {
            super(path);
            this.contents = requireNonNull(contents);
        }

        @Override
        public byte[] loadResource() {
            return this.contents.get();
        }
    }

    private Node newLink(JrtPath path, Node target) {
        return nodeCache.computeIfAbsent(path, p -> new LinkNode(p, target));
    }

    private static class LinkNode extends Node {
        private final Node target;

        private LinkNode(JrtPath path, Node target) {
            super(path);
            this.target = requireNonNull(target);
        }

        @Override
        public boolean isLink() {
            return true;
        }

        @Override
        public Node resolve() {
            return target;
        }
    }

    private static class Memoized<T> implements Supplier<T> {
        private volatile Supplier<T> source;
        private volatile T value = null;

        static <T> Memoized<T> of(Supplier<T> source) {
            return new Memoized<>(source);
        }

        private Memoized(Supplier<T> source) {
            this.source = requireNonNull(source);
        }

        @Override
        public T get() {
            T value = this.value;
            if (value == null) {
                Supplier<T> source = this.source;
                if (source != null) {
                    value = this.value = source.get();
                    this.source = null;
                } else {
                    // Race: If source is null, value must have been,
                    // written so a non-null value can *now* be read.
                    value = this.value;
                }
            }
            return value;
        }
    }
}
