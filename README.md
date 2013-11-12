![](https://raw.github.com/wiki/EsotericSoftware/scar/images/logo.png)

## Overview

Scar is a collection of utilities that make it easier to do build related tasks using Java code. Building with Java code makes sense for Java developers. Rather than a complicated tool that caters to all needs, Scar is a simple tool that allows you to get what you need done in a simple, straightforward manner. Familiar tools and libraries can be used. Builds can be debugged like any other Java program. There is no new language to learn or XML attributes to look up.

## Getting started

Scar has the following key components:

- The `Scar` class has static [utility methods](#Scar_utility_methods) for various tasks such as zip, unzip, jar, compile, etc.
- The `Paths` class conveniently collects file paths using globs (asterisks) or regex.
- The `Project` class is a generic project descriptor. It holds strings, numbers, paths, and other data about a project.
- The `Build` class takes a Project and uses the Scar class to compile sources and package a JAR.

There are generally two ways of using Scar:

### Code

Create a new class and add a main method. Import the `Scar` and `Paths` classes, and use them to do whatever tasks you need: glob paths, zip files, etc.

### Build class

Use the `Build` class to do common build tasks on a project descriptor. The project descriptor is simply a map with convenience methods to get values (eg, as a list of file paths) and to do token replacement. The `Build` class looks in the project for specific values (source paths, dependency JARs, etc) and then compiles Java source and creates a JAR. For example, this builds a JAR from a project directory using the default project values:

    Project project = Build.project("path to project dir");
    Build.build(project);

Running Scar on a directory runs Build.main which does the above two lines of code.

    scar /path_to_project_dir/

If the directory contains a "project.yaml" file, it is used to customize the project values. When writing code to build a project instead of the command line, the project values can be customized in code.

## Projects

The `Project` class is a project descriptor. It consists of a path to the root directory of the project and a HashMap holding some data. The `Project` class itself is a generic data structure and doesn't know anything about how the data will be used. The requirements for what should be specified in the project are determined by the tasks that need to be performed.

Projects can be defined entirely in Java:

    Project project = new Project();
    project.set("name", "AwesomeLib");
    ArrayList sourceDirs = new ArrayList();
    sourceDirs.add("src");
    sourceDirs.add("other");
    project.set("source", sourceDirs);

Projects can also be loaded from a [YAML](http://www.yaml.org/) file on disk. YAML is a human readable format that makes it easy to specify the project object graph. Scar uses the [YamlBeans](http://code.google.com/p/yamlbeans/) library to parse YAML.

    name: AwesomeLib
    source:
    - src
    - other

    Project project = new Project("project.yaml");

Separate from the object graph, a project can also store a "document" string. What is done with this string is left up to the code that is actually performing the tasks. The document can be set in Java with the `Project#setDocument(String)` method or in YAML by using the document separator "---":

    name: AwesomeLib
    source:
    - src
    - other
    ---
    System.out.println("The document data can be any text, ");
    System.out.println("but is a convenient place to put code.");

## Paths

Scar uses the [Wildcard](http://code.google.com/p/wildcard/) library to collect and manipulate files. For many tasks, the majority of the work is collecting the files to act upon, and Wildcard makes this easy. See the [Wildcard documentation](http://code.google.com/p/wildcard/) for how to construct paths.

Paths used in project YAML files can be either a single entry or a list. They should use the [pipe delimited patterns](http://code.google.com/p/wildcard/#Pipe_delimited_patterns) format. Some [YAML examples](http://code.google.com/p/scar/#YAML_examples) are provided below.

## The Build class

The `Build` class has static methods that take a `Project` and perform various tasks, such as compile and JAR. The `Scar` class actually performs most tasks, the `Build` class just defines how to project descriptors are interpreted. Note that using the `Project` and `Build` classes are optional. Your own customized build system could be implemented using only the `Scar` and `Paths` classes.

The `Build` class has the following conventions for the data in the project descriptor:

<table>
  <tr><td>**Property**</td><td>**Description**</td></tr>
  <tr><td>name</td><td>The name of the project. Used to name the JAR.<br>Default: The name of the directory containing the project YAML file.</td></tr>
  <tr><td>target</td><td>The directory to output build artifacts.<br>Default: The directory containing the project YAML file, plus "../target/`name`".</td></tr>
  <tr><td>version</td><td>The version of the project. If available, used to name the JAR.<br>Default: *blank*</td></tr>
  <tr><td>resources</td><td>Wildcard patterns for the files to include in the JAR.<br>Default: `resources`, `src/main/resources`, and 'assets'.</td></tr>
  <tr><td>dist</td><td>Wildcard patterns for the files to include in the distribution, outside the JAR.<br>Default: `dist`.</td></tr>
  <tr><td>source</td><td>Wildcard patterns for the Java files to compile.<br>Default: `src|**/*.java` and `src/main/java|**/*.java`.</td></tr>
  <tr><td>classpath</td><td>Wildcard patterns for the files to include on the classpath.<br>Default: `lib|**/*.jar` and `libs|**/*.jar`.</td></tr>
  <tr><td>dependencies</td><td>Relative or absolute paths to dependency project directories or YAML files.<br>Default: *blank*</td></tr>
  <tr><td>include</td><td>Relative or absolute paths to project files to inherit properties from.<br>Default: *blank*</td></tr>
  <tr><td>main</td><td>Name of the main class.<br>Default: *blank*</td></tr>
</table>

If any of these are defined in the project.yaml file, those values are used instead of the defaults.

### Building

When the Scar JAR is run from the command line, it creates a project for the current directory and calls `Build.build(project)`. This calls the `buildDependencies`, `clean`, `compile`, `jar`, and `dist` utility methods on the `Build` class. These methods respectively build all dependency projects (recursively), clean the output directory, compile sources to class files, JAR class files and resources, and place all distribution files and JARs needed to run the application in an output directory. If a main class was defined, the resulting JAR will have a manifest that allows it to be executed.

The project descriptor describes the project's files, and this is often a sufficient to completely build a Java project. If no project.yaml file is found, the defaults are used. If the defaults match your project, you don't even need a project.yaml file. However, it is often convenient to have one at least to specify a main class:

    main: com.example.MainClass

### Build customization

When the Scar JAR is run, if a project has a document string (text included after the YAML), the string is compiled as Java code and executed instead of calling `Build.build(project)`. The code will be executed with a static import for `Scar` and the project instance is available through a variable named `project`. See `Scar.executeCode()` for more details about how the code is compiled and run.

Here is an example project descriptor that does the default build and then signs the JARs for use with Java WebStart:

    source: tools|**.java
    resources:
    - images
    - fonts|!arial.ttf
    main: com.example.MainClass
    ---
    Build.build(project);
    keystore("keystore", "alias", "password", "Company", "Title");
    Build.jws(project, false, "keystore", "alias", "password");

## Scar utility methods

The static methods on the `Scar` class, along with the `Paths` class, are really what makes building using Java code manageable. Some of the methods are listed below. Please refer to the [javadocs](http://scar.googlecode.com/svn/api/com/esotericsoftware/scar/Scar.html) for exactly how to use each method.

- compile: Compiles Java source to class files.
- jar: Puts files in a JAR file, optionally generating a manifest to make the JAR executable.
- oneJar: Unzips multiple JARs and repackages them into a single JAR.
- keystore: Generatesa keystore for signing JARs.
- sign/unsign: Cryptographically signs JAR files.
- pack200/unpack200: Encodes JAR files with pack200.
- gzip/ungzip: Encodes files with GZIP.
- zip/unzip: Encodes files with ZIP.
- lzma/unlzma: Encodes files with LZMA.
- shell: Executes shell commands.
- copyFile/moveFile/delete/mkdir: Manipulate files.
- executeCode: Compiles and executes a string as if it were a Java method body.
- ftpUpload: Uploads files via FTP.
- jws: Prepares JARs to be deployed with Java WebStart. Removes any previous signing, does pack200 and unpack200 to normalize the JAR, signs it with a keystore, does pack200, and then GZIP.
- jnlp: Generates a JNLP file referencing all the JARs for Java WebStart.
- jwsHtaccess: Generates .htaccess and VAR "type map" files that allow Apache to serve both pack200/GZIP JARs and regular JARs, based on capability of the client requesting the JAR.
- lwjglApplet: Prepares JARs to be deployed as an [LWJGL](http://lwjgl.org/) applet. Removes any previous signing, does pack200 and unpack200 to normalize the JAR, signs it with your keystore, does pack200, and then LZMA.
- lwjglAppletHtml: Generates an HTML file referencing all the JARs for an applet.

Scar.jwsHtaccess(project) generates ".htaccess" and "type map" VAR files in the `jws` directory. These files allow Apache to serve both pack200/gzipped JARs and regular JARs, based on capability of the client requesting the JAR. [More information](http://joust.kano.net/weblog/archive/2004/10/16/pack200-on-apache-web-server/).

## Logging

Scar uses the [MinLog](http://code.google.com/p/minlog/) library for logging. Little is logged at the `INFO` level, which is the default. The `DEBUG` and `TRACE` levels can be enabled for increasingly more information:

    Log.DEBUG();
    Log.TRACE();

Besides logging, since Scar is simply Java code, a build can be run through a debugger to quickly figure out problems.

## YAML examples

Wildcard properties can be specified as a single item:

    source: src|**/*.java
    resources: resources
    classpath: lib|**/*.jar
    dist: dist

Or as a list:

    source:
    - src|**/*.java
    - tools|**/*.java
    resources:
    - resources/main|!**/test/**
    - resources/images|*.png|*.jpg
    classpath: lib|**/*.jar
    dist: dist

A more complex example that uses all the properties:

    include: ../common.yaml
    version: 1.2-alpha3
    source:
    - src/main|**/*.java|!**Test.java
    - src/tools|**/*.java
    resources:
    - resources/main|!**/test/**
    - resources/images|*.png|*.jpg
    classpath:
    - lib|**/*.jar
    - some/class/dir|**/*.class
    dist: dist
    dependencies:
    - ../common-tools
    - ../supporting-lib/special.yaml
    - /some/absolute/project/path
    main: com.example.SomeClass
    target: build
    ---
    classpath build/tools.jar;
    import java.io.File;
    DEBUG();
    Build.compile(project);
    Build.jar(project);
    Build.dist(project);
    keystore("keystore", "alias", "password", "Company", "Title");
    Build.jws(project, true, "keystore", "alias", "password");
    Build.jwsHtaccess(project);
    Build.jnlp(project, "http://example.com/run.jnlp", "Company", "Title", "splash.png");
