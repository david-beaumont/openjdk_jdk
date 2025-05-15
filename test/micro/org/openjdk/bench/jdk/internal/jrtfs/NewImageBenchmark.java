/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.jdk.internal.jrtfs;

import jdk.internal.jimage.ImageReader;
import jdk.internal.jrtfs.NewImage;
import jdk.internal.jrtfs.NewResourceProvider;
import jdk.internal.jrtfs.SystemImage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 10, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = {
        "--add-exports", "java.base/jdk.internal.jimage=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.jrtfs=ALL-UNNAMED"})
public class NewImageBenchmark {

    /// NOT a @State since that causes setUp()/tearDown() to be shared.
    static class BaseColdStartState {
        protected Path imageFile;
        protected ByteOrder byteOrder;
        long count = -1;

        public void setUp() throws IOException {
            imageFile = Files.createTempFile("copied_jimage", "");
            byteOrder = ByteOrder.nativeOrder();
            Files.copy(SystemImage.moduleImageFile, imageFile, REPLACE_EXISTING);
        }

        public void tearDown() throws IOException {
            Files.deleteIfExists(imageFile);
            System.err.println("Node count = " + count);
        }
    }

    @State(Scope.Benchmark)
    public static class ColdStart extends BaseColdStartState {
        @Setup(Level.Iteration)
        public void setUp() throws IOException {
            super.setUp();
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            super.tearDown();
        }
    }

    @State(Scope.Benchmark)
    public static class ColdStartImageReader extends BaseColdStartState {
        ImageReader reader;

        @Setup(Level.Iteration)
        public void setup() throws IOException {
            super.setUp();
            reader = ImageReader.open(imageFile, byteOrder);
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            reader.close();
            super.tearDown();
        }
    }

    @State(Scope.Benchmark)
    public static class ColdStartNewImage extends BaseColdStartState {
        NewResourceProvider provider;
        NewImage image;

        @Setup(Level.Iteration)
        public void setup() throws IOException {
            super.setUp();
            provider = new NewResourceProvider(imageFile, byteOrder);
            image = new NewImage(provider, false);
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            provider.close();
            super.tearDown();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    public void coldStart_InitAndCount_NewImage(ColdStart state) throws IOException {
        try (var provider = new NewResourceProvider(state.imageFile, state.byteOrder)) {
            NewImage image = new NewImage(provider, false);
            state.count = countAllNodes(image.findNode("").get(), 0L, 2);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    public void coldStart_InitAndCount_ImageReader(ColdStart state) throws IOException {
        try (var reader = ImageReader.open(state.imageFile, state.byteOrder)) {
            state.count = countAllNodes(reader.findNode("/"), 0L);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    public void coldStart_CountOnly_NewImage(ColdStartNewImage state) throws IOException {
        state.count = countAllNodes(state.image.findNode("").get(), 0L, 2);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    public void coldStart_CountOnly_ImageReader(ColdStartImageReader state) throws IOException {
        state.count = countAllNodes(state.reader.findNode("/"), 0L);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void countAllNodes_NewImage(ColdStartNewImage state) throws IOException {
        state.count = countAllNodes(state.image.findNode("").get(), 0L, 2);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void countAllNodes_ImageReader(ColdStartImageReader state) throws IOException {
        state.count = countAllNodes(state.reader.findNode("/"), 0L);
    }

    static long countAllNodes(NewImage.Node node, long count, int depth) {
        count += 1;
        if (depth-- > 0 && node.isDirectory()) {
            for (var child : node.getChildren()) {
                count = countAllNodes(child, count, depth);
            }
        }
        return count;
    }

    static long countAllNodes(ImageReader.Node node, long count) {
        count += 1;
        //System.err.println("Node: " + node.getNameString());
        if (node.isDirectory()) {
            for (var child : node.getChildren()) {
                count = countAllNodes(child, count);
            }
        }
        return count;
    }
}