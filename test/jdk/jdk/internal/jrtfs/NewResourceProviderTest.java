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
import jdk.internal.jrtfs.NewResourceProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * @test
 * @bug 8352750
 * @summary Tests NewImage prototype.
 * @modules java.base/jdk.internal.jrtfs
 * @run junit/othervm -ea -esa NewResourceProviderTest
 */
public class NewResourceProviderTest {

    private static final List<String> ALL_MODULE_NAMES = asList(
            "java.base", "java.compiler", "java.datatransfer", "java.desktop",
            "java.instrument", "java.logging", "java.management",
            "java.management.rmi", "java.naming", "java.net.http", "java.prefs",
            "java.rmi", "java.scripting", "java.se", "java.security.jgss",
            "java.security.sasl", "java.smartcardio", "java.sql",
            "java.sql.rowset", "java.transaction.xa", "java.xml",
            "java.xml.crypto", "jdk.accessibility", "jdk.attach",
            "jdk.charsets", "jdk.compiler", "jdk.crypto.cryptoki",
            "jdk.crypto.ec", "jdk.dynalink", "jdk.editpad", "jdk.graal.compiler",
            "jdk.graal.compiler.management", "jdk.hotspot.agent", "jdk.httpserver",
            "jdk.incubator.vector", "jdk.internal.ed", "jdk.internal.jvmstat",
            "jdk.internal.le", "jdk.internal.md", "jdk.internal.opt",
            "jdk.internal.vm.ci", "jdk.jartool", "jdk.javadoc", "jdk.jcmd",
            "jdk.jconsole", "jdk.jdeps", "jdk.jdi", "jdk.jdwp.agent", "jdk.jfr",
            "jdk.jlink", "jdk.jpackage", "jdk.jshell", "jdk.jsobject", "jdk.jstatd",
            "jdk.localedata", "jdk.management", "jdk.management.agent",
            "jdk.management.jfr", "jdk.naming.dns", "jdk.naming.rmi", "jdk.net",
            "jdk.nio.mapmode", "jdk.sctp", "jdk.security.auth", "jdk.security.jgss",
            "jdk.unsupported", "jdk.unsupported.desktop", "jdk.xml.dom", "jdk.zipfs");

    private static final List<String> FIRST_PACKAGE_NAMES = Arrays.asList(
            "com", "com.sun", "com.sun.accessibility", "com.sun.accessibility.internal",
            "com.sun.accessibility.internal.resources", "com.sun.beans",
            "com.sun.beans.decoder", "com.sun.beans.editors", "com.sun.beans.finder");

    private static final List<String> LAST_PACKAGE_NAMES = Arrays.asList(
            "sun.util.resources", "sun.util.resources.cldr", "sun.util.resources.cldr.ext",
            "sun.util.resources.cldr.provider", "sun.util.resources.ext",
            "sun.util.resources.provider", "sun.util.spi", "toolbarButtonGraphics",
            "toolbarButtonGraphics.development", "toolbarButtonGraphics.general",
            "toolbarButtonGraphics.navigation", "toolbarButtonGraphics.text");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testBasics(boolean isPreviewMode) throws IOException {
        try (NewResourceProvider provider = new NewResourceProvider(
                Path.of("/home/jvm-compiler-spare/dev/openjdk/valhalla/build/linux-x86_64-server-fastdebug/images/jdk/lib/modules"),
                ByteOrder.nativeOrder())) {
            assertEquals(ALL_MODULE_NAMES, provider.getAllModuleNames().stream().toList());
            // Since there are over 1000 package names, just check the first/last
            // few elements and ensure they are ordered as expected.
            Set<String> packageNames = provider.getPackageNames(isPreviewMode);
            assertEquals(
                    FIRST_PACKAGE_NAMES,
                    packageNames.stream().limit(FIRST_PACKAGE_NAMES.size()).toList());
            assertEquals(
                    LAST_PACKAGE_NAMES,
                    packageNames.stream().skip(packageNames.size() - LAST_PACKAGE_NAMES.size()).toList());
            assertEquals(
                    Set.of("java.base", "java.instrument", "java.management"),
                    provider.getModulesForPackage("java.lang", isPreviewMode));
            assertEquals(
                    Set.of("java.desktop"),
                    provider.getModulesForPackage("com.sun.beans", isPreviewMode));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testWithImage(boolean isPreviewMode) throws IOException {
        try (NewResourceProvider provider = new NewResourceProvider(
                Path.of("/home/jvm-compiler-spare/dev/openjdk/valhalla/build/linux-x86_64-server-fastdebug/images/jdk/lib/modules"),
                ByteOrder.nativeOrder())) {

            NewImage image = new NewImage(provider, isPreviewMode);

            Node resource = assertNode(image, "/modules/java.base/java/lang/Integer.class");
            assertFalse(resource.isDirectory());
            assertFalse(resource.isLink());
            assertTrue(resource.getContent().length > 0);

            Node dir = assertNode(image, "/modules/java.base/java/lang");
            assertTrue(dir.isDirectory());
            assertFalse(dir.isLink());
            assertTrue(dir.getChildren().contains(resource));

            Node link = assertNode(image, "/packages/java.lang/java.base");
            assertFalse(link.isDirectory());
            assertTrue(link.isLink());
            Node moduleRoot = assertNode(image, "/modules/java.base");
            assertSame(moduleRoot, link.resolveLink(false));

            Node linkDir = assertNode(image, "/packages/java.lang");
            assertTrue(dir.isDirectory());
            assertFalse(dir.isLink());
            assertTrue(linkDir.getChildren().contains(link));
        }
    }

    static Node assertNode(NewImage image, String absPath) {
        Optional<Node> node = image.findNode(absPath);
        assertTrue(node.isPresent(), "Expected node at path: " + absPath);
        return node.get();
    }
}
