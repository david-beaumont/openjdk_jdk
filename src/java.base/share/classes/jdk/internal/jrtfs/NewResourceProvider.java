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

import jdk.internal.jimage.BasicImageReader;
import jdk.internal.jimage.ImageLocation;
import jdk.internal.jrtfs.NewImage.Node;
import jdk.internal.jrtfs.NewImage.NodeFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/// Provider of JImage resources for the system image.
///
/// ### JImage file structure
///
/// JImage uses path strings of the form `/<module>/<resource-path>` to identify
/// resources. If present, resources can be processed by getting an associated
/// `ImageLocation` instance.
///
/// For a "normal" file resource (e.g. "/java.base/java/lang/Integer.class"),
/// ImageLocation contains the following information:
///
///   * `getModule()` is the module name (e.g. "java.base")
///   * `getParent()` is the parent directory path (e.g. "java/lang")
///   * `getBase()` and `getExtension()` combine to provide the base file name
///      (e.g. "Integer" & "class").
///   * The content is the underlying resource file content.
///
/// While "normal" file entries are located by using the real module name (e.g.
/// "java.base"), there are two other pseudo-module names, which act as the roots
/// for directory information:
///
///  1. `modules`: The directory structure in the `/modules/...` namespace
///      (e.g. "/modules/java.base/java/lang").
///  2. `packages`: The file and directory structure in the `/packages/...`
///      namespace (e.g. "/packages/java.lang/java.base").
///
/// In these special namespaces, ImageLocation has a different interpretation.
///
/// For all `modules` paths (e.g. "/modules" or "/modules/java.base/java/lang")
/// or *one and two segment* `packages` paths (e.g. "/packages" or
/// "/packages/java.lang"), the ImageLocation represents a directory:
///
///  * `getModule()` is the pseudo-module name (e.g. "modules" or "packages").
///  * `getBase()` is the relative path within the pseudo-module (e.g.
///    "java.base/java/lang").
///  * The content is the sequence of integer offsets to child entries (which
///    can include resource entries not in the "/modules/..." namespace).
///
/// For the three segment `packages` paths (e.g. "/packages/java.lang/java.base")
/// ImageLocation represents a symbolic link.
///
///  * `getModule()` is always "packages".
///  * `getBase()` is the relative path (e.g. "java.lang/java.base").
///  * The content is not applicable since the link information is encoded in
///    the path (e.g. it links to `/modules/java.base`).
///
/// ### Processing ImageLocation
///
/// As a consequence of the above, there are some useful invariants which can
/// be exploited when handling ImageLocation instances:
///
///  1. If `getModule()` is "modules", then the location represents a directory
///     (in fact it's quicker to test `getModuleOffset()` in real code).
///  2. If `getModule()` is "packages", then the location represents a directory
///     or a symbolic link (depending on the path length).
///  3. If `findLocation("modules", ...)` returns a location, or
///     `verifyLocation("modules", ...)` returns true, the named resource is a
///     directory.
///
public final class NewResourceProvider extends BasicImageReader implements NewImage.ResourceProvider {
    private final ImageLocation modulesRoot;
    private final ImageLocation packagesRoot;
    private final int modulesRootNameOffset;
    private final Set<String> allModuleNames;
    private final Map<String, Module> modules = new ConcurrentHashMap<>();

    public NewResourceProvider(Path imagePath, ByteOrder byteOrder) throws IOException {
        super(imagePath, byteOrder);
        this.modulesRoot = requireNonNull(findLocation("/modules"));
        this.packagesRoot = requireNonNull(findLocation("/packages"));
        this.modulesRootNameOffset =
                requireNonNull(findLocation("/modules/java.base")).getModuleOffset();
        this.allModuleNames = Collections.unmodifiableSet(loadModuleNames());
    }

    /// Returns whether a location is an entry in the `/modules/...` tree, which
    /// means it's a pseudo-directory from which child entries can be accessed.
    private boolean isModulesDirectory(ImageLocation loc) {
        return loc.getModuleOffset() == modulesRootNameOffset;
    }

    /// A resource path is `<module>` (top level) or `<module>/<path>`.
    ///
    /// It DOES NOT start with `/` or `/modules`, and IS NOT empty. However,
    /// it can reference a directory rather than just a "file" resource.
    ///
    /// Since non-empty results are cached, we expect this to be called at most
    /// once for each path.
    @Override
    public Optional<Node> getResource(String resourcePath, NodeFactory factory, boolean isPreview) {
        int sepIdx = resourcePath.indexOf('/');
        if (sepIdx == -1) {
            // No '/' in path, so it's a module name (and corresponds to a directory).
            if (resourcePath.isEmpty()) {
                return Optional.of(factory.newResourceDirectory(resourcePath));
            }
            return allModuleNames.contains(resourcePath)
                    ? Optional.of(factory.newResourceDirectory(resourcePath))
                    : Optional.empty();
        }
        // Module should exist and should be loadable (this is cached in the reader).
        String moduleName = resourcePath.substring(0, sepIdx);
        Module module = findModule(moduleName);
        return module != null ? module.getResourceNode(resourcePath, factory, isPreview) : Optional.empty();
    }

    ///  Additionally to above, the resourcePath can be empty, indicating the root.
    @Override
    public void forEachChildOf(NodeFactory factory, String resourcePath, boolean isPreview, Consumer<Node> action) {
        if (resourcePath.isEmpty()) {
            allModuleNames.forEach(name -> action.accept(factory.newResourceDirectory(name)));
            return;
        }
        int sepIdx = resourcePath.indexOf('/');
        String moduleName = sepIdx == -1 ? resourcePath : resourcePath.substring(0, sepIdx);
        Module module = findModule(moduleName);
        if (module != null) {
            module.forEachChild(resourcePath, factory, action, isPreview);
        }
    }

    @Override
    public Set<String> getModulesForPackage(String packageName, boolean withPreviewNames) {
        String packagePath = "/" + packageName.replace('.', '/');
        Set<String> modules = new LinkedHashSet<>();
        for (String moduleName : allModuleNames) {
            if (hasPackageDirectory(moduleName + packagePath)
                    || hasPackageDirectory(moduleName + PREVIEW_DIR + packagePath)) {
                modules.add(moduleName);
            }
        }
        return modules;
    }

    // Result is cached and this method is only called once.
    @Override
    public Set<String> getAllModuleNames() {
        return allModuleNames;
    }

    @Override
    public boolean packageExists(String moduleName, String packageName, boolean withPreviewPackages) {
        return hasPackageDirectory(moduleName + "/" + packageName.replace('.', '/'));
    }

    // Result is cached and this method is only called once.
    @Override
    public Set<String> getPackageNames(boolean withPreviewNames) {
        Set<String> names = new TreeSet<>();
        forEachChildLocation(packagesRoot, loc -> names.add(loc.getBase()));
        if (withPreviewNames) {
            for (String moduleName : allModuleNames) {
                // Check preview directory *before* creating the module.
                if (hasPackageDirectory(moduleName + PREVIEW_DIR)) {
                    names.addAll(getModule(moduleName).getPreviewOnlyPackageNames());
                }
            }
        }
        return names;
    }

    Set<String> loadModuleNames() {
        Set<String> names = new LinkedHashSet<>();
        forEachChildLocation(modulesRoot, loc -> names.add(loc.getBase()));
        return names;
    }

    Module findModule(String moduleName) {
        return allModuleNames.contains(moduleName)
                ? modules.computeIfAbsent(moduleName, this::newModuleRoot)
                : null;
    }

    Module getModule(String moduleName) {
        return requireNonNull(findModule(moduleName), "No such module");
    }

    private static final String PREVIEW_DIR = "/META-INF/preview";

    Module newModuleRoot(String moduleName) {
        return new Module(moduleName);
    }

    // Two cases for loc.getModule():
    // 1. getModule() == "modules" ==> entry is module metadata (a pseudo "directory").
    //    * getBase() is the relative module path (e.g. "java.base/jdk").
    //    * Content is byte[] of Integer offsets to ImageLocations of child entries.
    //
    // 2. Otherwise, entry is a resource.
    //    * getModule() is the module name (e.g. "java.base")
    //    * getParent() is the parent path (e.g. "java/lang")
    //    * getFullName() is an absolute resource path (e.g. "/java.base/java/lang/Integer.class").
    //    * getBase() is "Integer", but not useful in this context (needs an extension too).

    final class Module {
        private final String moduleName;
        private final String previewPrefix;
        private final Set<String> previewOnlyPackageNames;

        private Module(String moduleName) {
            this.moduleName = requireNonNull(moduleName);
            ImageLocation root = requireNonNull(findLocation("modules", moduleName));
            assert isModulesDirectory(root);
            // Instead of string comparison, test for "directories" via offset.
            this.previewPrefix = moduleName + PREVIEW_DIR;
            this.previewOnlyPackageNames = collectPreviewOnlyPackageNames();
        }

        Set<String> getPreviewOnlyPackageNames() {
            return previewOnlyPackageNames;
        }

        Optional<Node> getResourceNode(String resourcePath, NodeFactory factory, boolean isPreview) {
            String modPath = modulesPathOf(resourcePath, isPreview);
            ImageLocation dir = findLocation("modules", modPath);
            if (dir != null) {
                assert isModulesDirectory(dir);
                return Optional.of(factory.newResourceDirectory(resourcePath));
            }
            ImageLocation file = findLocation("/" + modPath);
            if (file != null) {
                return Optional.of(factory.newResource(resourcePath, () -> getResource(file)));
            }
            return Optional.empty();
        }

        void forEachChild(String resourcePath, NodeFactory factory, Consumer<Node> action, boolean isPreview) {
            ImageLocation dir = findLocation("modules", modulesPathOf(resourcePath, isPreview));
            if (dir == null) {
                return;
            }
            assert isModulesDirectory(dir);
            forEachChildLocation(dir, loc -> {
                if (isModulesDirectory(loc)) {
                    action.accept(factory.newResourceDirectory(resourcePathOfDir(loc, isPreview)));
                } else {
                    // Child is a resource, construct path using the base + extension.
                    StringBuilder resPath =
                            new StringBuilder(resourcePath).append('/').append(loc.getBase());
                    if (loc.getExtensionOffset() != 0) {
                        resPath.append('.').append(loc.getExtension());
                    }
                    action.accept(factory.newResource(resPath.toString(), () -> getResource(loc)));
                }
            });
        }

        private Set<String> collectPreviewOnlyPackageNames() {
            ImageLocation previewDir = findLocation("modules", previewPrefix);
            if (previewDir == null) {
                return Collections.emptySet();
            }
            assert isModulesDirectory(previewDir);
            Set<String> paths = new LinkedHashSet<>();
            recursivelyCollectPreviewPackageNames(previewDir, paths::add);
            return Collections.unmodifiableSet(paths);
        }

        private void recursivelyCollectPreviewPackageNames(ImageLocation dir, Consumer<String> collector) {
            forEachChildLocation(dir, loc -> {
                if (isModulesDirectory(loc)) {
                    // For preview directories, we must remove the "/META-INF/preview"
                    // part and create a non-preview path equivalent.
                    String relativePath = relativize(previewPrefix, loc.getBase());
                    // Only add packages which do not already appear in /modules/...
                    if (hasPackageDirectory(moduleName + "/" + relativePath)) {
                        collector.accept(relativePath.replace('/', '.'));
                    }
                    recursivelyCollectPreviewPackageNames(loc, collector);
                }
            });
        }

        private String modulesPathOf(String resourcePath, boolean isPreview) {
            assert isRelativeTo(moduleName, resourcePath);
            return isPreview
                    ? previewPrefix + resourcePath.substring(moduleName.length())
                    : resourcePath;
        }

        private String resourcePathOfDir(ImageLocation dir, boolean isPreview) {
            // Child is directory, "base" is directory path, including <module>.
            // This is normally the resource path, but in preview mode it contains
            // the preview path (which must be removed).
            String dirPath = dir.getBase();
            if (!isPreview) {
                assert isRelativeTo(moduleName, dirPath);
                return dirPath;
            }
            assert isRelativeTo(previewPrefix, dirPath);
            return moduleName + dirPath.substring(previewPrefix.length());
        }
    }

    /// Tests whether a given path starts with a prefix path.
    ///
    /// For the path`foo/bar`:
    ///  * `isRelativeTo("foo", "foo/bar") == true`
    ///  * `isRelativeTo("/foo", "/foo/bar") == true`: Leading '/' okay.
    ///  * `isRelativeTo("foo/bar", "foo/bar") == true`: Equal values okay.
    ///
    ///  But:
    ///  * `isRelativeTo("foo/", "foo/bar") == false`: Invalid trailing `/`.
    ///  * `isRelativeTo("foo/b", "foo/bar") == false`: Incomplete segment.
    ///
    /// NOT relative to `foo/b` (not a complete path
    /// segment), but it *is* relative to `foo/bar` (same path)
    private static boolean isRelativeTo(String prefix, String path) {
        int len = prefix.length();
        return path.startsWith(prefix) && (path.length() == len || path.charAt(len) == '/');
    }

    /// Removes a prefix from the given path (checking that it is relative to
    /// the specified prefix). The prefix MUST NOT be empty.
    ///
    /// If the path equals the prefix, the empty string is returned, otherwise
    /// the *relative* trailing path is returned (no leading `/`).
    private static String relativize(String prefix, String path) {
        assert !prefix.isEmpty() && isRelativeTo(prefix, path);
        int len = prefix.length();
        return path.length() > len ? path.substring(len + 1) : "";
    }

    /// Executes an action for each child location of the given directory.
    ///
    /// It is up to the caller to ensure that only locations corresponding
    /// to directories (e.g. paths in `/modules/...` or some `packages/...`)
    /// are passed to this function.
    private void forEachChildLocation(ImageLocation dir, Consumer<ImageLocation> action) {
        IntBuffer offsets =
                ByteBuffer.wrap(getResource(dir)).order(getByteOrder()).asIntBuffer();
        int count = offsets.capacity();
        for (int i = 0; i < count; i++) {
            action.accept(getLocation(offsets.get(i)));
        }
    }

    /// Tests for the existence of a module path such as:
    ///   * `java.base/java/lang/Integer.class`
    ///   * `java.base/META-INF/preview/java/lang/Integer.class`
    ///
    /// @param modulesPath a relative path starting with a module name.
    private boolean hasPackageDirectory(String modulesPath) {
        return verifyLocation("modules", modulesPath);
    }
}
