/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.runtimeshaded;

import org.gradle.api.DefaultTask;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.internal.file.collections.DirectoryFileTree;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.ErroringAction;
import org.gradle.internal.IoActions;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WARNING!
 *
 * THIS IMPLEMENTATION IS COPIED FROM dependency-management/src/main/java/org/gradle/api/internal/runtimeshaded/PackageListGenerator.java
 *
 * AND NEEDS TO BE REPLACED WITH IT AS SOON AS WE BUILD WITH A VERSION OF GRADLE THAT BUNDLES THIS TASK!
 */
public class PackageListGenerator extends DefaultTask {
    public static final List<String> DEFAULT_EXCLUDES = Arrays.asList(
        "org/gradle",
        "java",
        "javax",
        "groovy",
        "groovyjarjarantlr",
        "net/rubygrapefruit",
        "org/codehaus/groovy",
        "org/apache/tools/ant",
        "org/apache/commons/logging",
        "org/slf4j",
        "org/apache/log4j",
        "org/apache/xerces",
        "org/w3c/dom",
        "org/xml/sax");

    private File outputFile;
    private FileCollection classpath;
    private List<String> excludes;

    public PackageListGenerator() {
        excludes = DEFAULT_EXCLUDES;
    }

    @InputFiles
    public FileCollection getClasspath() {
        return classpath;
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath;
    }

    @OutputFile
    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    @Input
    public List<String> getExcludes() {
        return excludes;
    }

    public void setExcludes(List<String> excludes) {
        this.excludes = excludes;
    }

    @TaskAction
    public void generate() {
        IoActions.writeTextFile(outputFile, new ErroringAction<BufferedWriter>() {
            @Override
            public void doExecute(final BufferedWriter bufferedWriter) throws Exception {
                Trie packages = collectPackages();
                packages.dump(false, new ErroringAction<String>() {
                    @Override
                    public void doExecute(String s) throws Exception {
                        bufferedWriter.write(s);
                        bufferedWriter.newLine();
                    }
                });
            }
        });
    }

    private Trie collectPackages() throws IOException {
        Trie.Builder builder = new Trie.Builder();
        for (File file : classpath) {
            if (file.exists()) {
                if (file.getName().endsWith(".jar")) {
                    processJarFile(file, builder);
                } else {
                    processDirectory(file, builder);
                }
            }
        }
        return builder.build();
    }

    private void processDirectory(File file, final Trie.Builder builder) {
        new DirectoryFileTree(file).visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails dirDetails) {
            }

            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                try {
                    ZipEntry zipEntry = new ZipEntry(fileDetails.getPath());
                    InputStream inputStream = fileDetails.open();
                    try {
                        processEntry(zipEntry, builder);
                    } finally {
                        inputStream.close();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    private void processJarFile(File file, final Trie.Builder builder) throws IOException {
        IoActions.withResource(openJarFile(file), new ErroringAction<ZipInputStream>() {
            @Override
            protected void doExecute(ZipInputStream inputStream) throws Exception {
                ZipEntry zipEntry = inputStream.getNextEntry();
                while (zipEntry != null) {
                    processEntry(zipEntry, builder);
                    zipEntry = inputStream.getNextEntry();
                }
            }
        });
    }

    private void processEntry(ZipEntry zipEntry, Trie.Builder builder) throws IOException {
        String name = zipEntry.getName();
        if (name.endsWith(".class")) {
            processClassFile(zipEntry, builder);
        }
    }

    private void processClassFile(ZipEntry zipEntry, Trie.Builder builder) throws IOException {
        int endIndex = zipEntry.getName().lastIndexOf("/");
        if (endIndex > 0) {
            String className = zipEntry.getName().substring(0, endIndex);
            for (String exclude : getExcludes()) {
                if (className.startsWith(exclude)) {
                    return;
                }
            }
            builder.addWord(className);
        }
    }

    private static ZipInputStream openJarFile(File file) throws IOException {
        return new ZipInputStream(new FileInputStream(file));
    }

}
