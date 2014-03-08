
package com.esotericsoftware.scar;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.esotericsoftware.wildcard.Paths;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.scar.Scar.*;

public class Build extends Project {
	/** List of project names that have been built. {@link Build#buildDependencies(Project)} will skip any projects with a matching
	 * name. */
	static public final List<String> builtProjects = new ArrayList();

	/** Loads the specified project with default values and loads any other projects needed for the "include" property.
	 * @param path Path to a YAML project file, or a directory containing a "project.yaml" file. */
	static public Project project (String path) throws IOException {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		Project defaults = new Project();

		File file = new File(canonical(path));
		if (file.isDirectory()) {
			String name = file.getName();
			defaults.set("name", name);
			defaults.set("target", file.getParent() + "/target/" + name + "/");
		} else {
			String name = file.getParentFile().getName();
			defaults.set("name", name);
			defaults.set("target", file.getParentFile().getParent() + "/target/" + name + "/");
		}

		ArrayList libs = new ArrayList();
		libs.add("lib|**/*.jar");
		libs.add("libs|**/*.jar");
		defaults.set("classpath", libs);

		defaults.set("dist", "dist");

		ArrayList source = new ArrayList();
		source.add("src|**/*.java");
		source.add("src/main/java|**/*.java");
		defaults.set("source", source);

		ArrayList resources = new ArrayList();
		resources.add("assets");
		resources.add("resources");
		resources.add("src/main/resources");
		defaults.set("resources", resources);

		Project project = project(path, defaults);

		// Remove dependency if a JAR of the same name is on the classpath.
		Paths classpath = project.getPaths("classpath");
		classpath.add(dependencyClasspaths(project, classpath, false, false));
		for (String dependency : project.getList("dependencies")) {
			String dependencyName = project(project.path(dependency)).get("name");
			for (String classpathFile : classpath) {
				String name = fileWithoutExtension(classpathFile);
				int dashIndex = name.lastIndexOf('-');
				if (dashIndex != -1) name = name.substring(0, dashIndex);
				if (name.equals(dependencyName)) {
					if (DEBUG) {
						debug(project.toString(), "Ignoring dependency: " + dependencyName + " (already on classpath: " + classpathFile
							+ ")");
					}
					project.remove("dependencies", dependency);
					break;
				}
			}
		}

		return project;
	}

	/** Loads the specified project with the specified defaults and loads any other projects needed for the "include" property.
	 * @param path Path to a YAML project file, or a directory containing a "project.yaml" file. */
	static public Project project (String path, Project defaults) throws IOException {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");
		if (defaults == null) throw new IllegalArgumentException("defaults cannot be null.");

		Project actualProject = new Project(path);

		Project project = new Project();
		project.replace(defaults);

		File parent = new File(actualProject.getDirectory()).getParentFile();
		while (parent != null) {
			File includeFile = new File(parent, "include.yaml");
			if (includeFile.exists()) {
				try {
					project.replace(project(includeFile.getAbsolutePath(), defaults));
				} catch (RuntimeException ex) {
					throw new RuntimeException("Error loading included project: " + includeFile.getAbsolutePath(), ex);
				}
			}
			parent = parent.getParentFile();
		}

		for (String include : actualProject.getList("include")) {
			try {
				project.replace(project(actualProject.path(include), defaults));
			} catch (RuntimeException ex) {
				throw new RuntimeException("Error loading included project: " + actualProject.path(include), ex);
			}
		}
		project.replace(actualProject);
		return project;
	}

	/** Deletes the "target" directory and all files and directories under it. */
	static public void clean (Project project) {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "Clean");
		if (TRACE) trace(project.toString(), "Deleting: " + project.path("$target$"));
		paths(project.path("$target$")).delete();
	}

	/** Computes the classpath for the specified project and all its dependency projects, recursively. */
	static public Paths classpath (Project project, boolean errorIfDepenenciesNotBuilt) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		Paths classpath = project.getPaths("classpath");
		classpath.add(dependencyClasspaths(project, classpath, true, errorIfDepenenciesNotBuilt));
		return classpath;
	}

	/** Computes the classpath for all the dependencies of the specified project, recursively. */
	static private Paths dependencyClasspaths (Project project, Paths paths, boolean includeDependencyJAR,
		boolean errorIfDepenenciesNotBuilt) throws IOException {
		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(project.path(dependency));
			String dependencyTarget = dependencyProject.path("$target$/");
			if (errorIfDepenenciesNotBuilt && !fileExists(dependencyTarget))
				throw new RuntimeException("Dependency has not been built: " + dependency + "\nAbsolute dependency path: "
					+ canonical(dependency) + "\nMissing dependency target: " + canonical(dependencyTarget));
			if (includeDependencyJAR) paths.glob(dependencyTarget, "*.jar");
			paths.add(classpath(dependencyProject, errorIfDepenenciesNotBuilt));
		}
		return paths;
	}

	/** Collects the source files using the "source" property and compiles them into a "classes" directory under the target
	 * directory. It uses "classpath" and "dependencies" to find the libraries required to compile the source.
	 * <p>
	 * Note: Each dependency project is not built automatically. Each needs to be built before the dependent project.
	 * @return The path to the "classes" directory. */
	static public String compile (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		Paths classpath = classpath(project, true);
		Paths source = project.getPaths("source");

		if (INFO) info(project.toString(), "Compile");
		if (DEBUG) {
			debug(project.toString(), "Source: " + source.count() + " files");
			debug(project.toString(), "Classpath: " + classpath);
		}

		String classesDir = mkdir(project.path("$target$/classes/"));

		Scar.compile(source, classpath, classesDir, project.get("compileTarget", "1.6"));
		return classesDir;
	}

	/** Collects the class files from the "classes" directory and all the resource files using the "resources" property and encodes
	 * them into a JAR file.
	 * 
	 * If the resources don't contain a META-INF/MANIFEST.MF file, one is generated. If the project has a main property, the
	 * generated manifest will include "Main-Class" and "Class-Path" entries to allow the main class to be run with "java -jar".
	 * @return The path to the created JAR file. */
	static public String jar (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "JAR");

		String jarDir = mkdir(project.path("$target$/jar/"));

		String classesDir = project.path("$target$/classes/");
		paths(classesDir, "**/*.class").copyTo(jarDir);
		project.getPaths("resources").copyTo(jarDir);

		String jarFile;
		if (project.has("version"))
			jarFile = project.path("$target$/$name$-$version$.jar");
		else
			jarFile = project.path("$target$/$name$.jar");

		Jar.jar(jarFile, jarDir, project.get("main"), classpath(project, true));
		return jarFile;
	}

	/** Collects the distribution files using the "dist" property, the project's JAR file, and everything on the project's classpath
	 * (including dependency project classpaths) and places them into a "dist" directory under the "target" directory. This is also
	 * done for depenency projects, recursively. This is everything the application needs to be run from JAR files.
	 * @return The path to the "dist" directory. */
	static public String dist (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "Dist");

		String distDir = mkdir(project.path("$target$/dist/"));
		classpath(project, true).copyTo(distDir);
		Paths distPaths = project.getPaths("dist");
		dependencyDistPaths(project, distPaths);
		distPaths.copyTo(distDir);
		paths(project.path("$target$"), "*.jar").copyTo(distDir);
		return distDir;
	}

	static private Paths dependencyDistPaths (Project project, Paths paths) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(project.path(dependency));
			String dependencyTarget = dependencyProject.path("$target$/");
			if (!fileExists(dependencyTarget)) throw new RuntimeException("Dependency has not been built: " + dependency);
			paths.glob(dependencyTarget + "dist", "!*/**.jar");
			paths.add(dependencyDistPaths(dependencyProject, paths));
		}
		return paths;
	}

	/** Copies all the JAR and JNLP files from the "dist" directory to a "jws" directory under the "target" directory. It then uses
	 * the specified keystore to sign each JAR. If the "pack" parameter is true, it also compresses each JAR using pack200 and
	 * GZIP. */
	static public void jws (Project project, boolean pack, String keystoreFile, String alias, String password) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		if (INFO) info(project.toString(), "JWS");

		String jwsDir = mkdir(project.path("$target$/jws/"));
		String distDir = project.path("$target$/dist/");
		Scar.jws(distDir, jwsDir, pack, keystoreFile, alias, password);
	}

	/** Generates ".htaccess" and "type map" VAR files in the "jws" directory. These files allow Apache to serve both pack200/GZIP
	 * JARs and regular JARs, based on capability of the client requesting the JAR. */
	static public void jwsHtaccess (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "JWS htaccess");

		Scar.jwsHtaccess(mkdir(project.path("$target$/jws/")));
	}

	/** Generates a JNLP file in the "jws" directory. JARs in the "jws" directory are included in the JNLP. JARs containing "native"
	 * and "win", "mac", "linux", or "solaris" are properly included in the native section of the JNLP. The "main" property is used
	 * for the main class in the JNLP.
	 * @param splashImage Can be null. */
	static public void jnlp (Project project, String url, String company, String title, String splashImage) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		if (url == null) throw new IllegalArgumentException("url cannot be null.");
		if (!url.startsWith("http")) throw new RuntimeException("Invalid url: " + url);
		if (company == null) throw new IllegalArgumentException("company cannot be null.");
		if (title == null) throw new IllegalArgumentException("title cannot be null.");

		if (DEBUG)
			debug(project.toString(), "JNLP (" + url + ", " + company + ", " + title + ", " + splashImage + ")");
		else if (INFO) //
			info(project.toString(), "JNLP");

		if (!project.has("main")) throw new RuntimeException("Unable to generate JNLP: project has no main class");

		String projectJarName;
		if (project.has("version"))
			projectJarName = project.format("$name$-$version$.jar");
		else
			projectJarName = project.format("$name$.jar");

		String jwsDir = mkdir(project.path("$target$/jws/"));
		Scar.jnlp(jwsDir, project.get("main"), projectJarName, url, title, company, splashImage);
	}

	static public String lwjglApplet (Project project, String keystoreFile, String alias, String password) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "LWJGL applet");

		String distDir = project.path("$target$/dist/");
		String appletDir = mkdir(project.path("$target$/applet-lwjgl/"));

		Scar.lwjglApplet(distDir, appletDir, keystoreFile, alias, password);

		return appletDir;
	}

	static public String lwjglAppletHtml (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "LWJGL applet HTML");

		String appletDir = mkdir(project.path("$target$/applet-lwjgl/"));

		Scar.lwjglAppletHtml(appletDir, project.get("main"));

		return appletDir;
	}

	/** Unzips all JARs in the "dist" directory and creates a single JAR containing those files in the "dist/onejar" directory. The
	 * manifest from the project's JAR is used. Putting everything into a single JAR makes it harder to see what libraries are
	 * being used, but makes it easier for end users to distribute the application.
	 * <p>
	 * Note: Files with the same path in different JARs will be overwritten. Files in the project's JAR will never be overwritten,
	 * but may overwrite other files.
	 * @param excludeJARs The names of any JARs to exclude. */
	static public void oneJAR (Project project, String... excludeJARs) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info(project.toString(), "One JAR");

		String onejarDir = mkdir(project.path("$target$/onejar/"));
		String distDir = project.path("$target$/dist/");
		String projectJarName;
		if (project.has("version"))
			projectJarName = project.format("$name$-$version$.jar");
		else
			projectJarName = project.format("$name$.jar");

		ArrayList<String> processedJARs = new ArrayList();
		outer:
		for (String jarFile : paths(distDir, "*.jar", "!" + projectJarName)) {
			String jarName = fileName(jarFile);
			for (String exclude : excludeJARs)
				if (jarName.equals(exclude)) continue outer;
			unzip(jarFile, onejarDir);
			processedJARs.add(jarFile);
		}
		unzip(distDir + projectJarName, onejarDir);

		String onejarFile;
		if (project.has("version"))
			onejarFile = project.path("$target$/dist/onejar/$name$-$version$-all.jar");
		else
			onejarFile = project.path("$target$/dist/onejar/$name$-all.jar");
		mkdir(parent(onejarFile));

		if (project.has("main")) new File(onejarDir, "META-INF/MANIFEST.MF").delete();

		Jar.jar(onejarFile, onejarDir, project.get("main"), classpath(project, true));
	}

	/** Calls {@link #build(Project)} for each dependency project in the specified project. */
	static public void buildDependencies (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(project.path(dependency));

			if (builtProjects.contains(dependencyProject.get("name"))) {
				if (DEBUG) debug(project.toString(), "Dependency project already built: " + dependencyProject);
				continue;
			}

			String jarFile;
			if (dependencyProject.has("version"))
				jarFile = dependencyProject.path("$target$/$name$-$version$.jar");
			else
				jarFile = dependencyProject.path("$target$/$name$.jar");

			if (DEBUG) debug("Building dependency: " + dependencyProject);
			if (!executeDocument(dependencyProject)) build(dependencyProject);
		}
	}

	/** Calls {@link #project(String)} and then {@link #build(Project)}. */
	static public void build (String path) throws IOException {
		build(project(path));
	}

	/** Executes the buildDependencies, clean, compile, jar, and dist utility metshods. */
	static public void build (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		buildDependencies(project);

		if (INFO) info(project.toString(), "Target: " + project.path("$target$"));

		clean(project);
		try {
			Thread.sleep(100);
		} catch (InterruptedException ignored) {
		}
		compile(project);
		try {
			Thread.sleep(100);
		} catch (InterruptedException ignored) {
		}
		jar(project);
		dist(project);

		if (paths(project.path("$target$")).filesOnly().isEmpty()) {
			if (WARN) warn(project.toString(), "Empty target folder.");
			delete(project.path("$target$"));
		}

		builtProjects.add(project.get("name"));
	}

	/** Executes Java code in the specified project's document, if any.
	 * @return true if code was executed. */
	static public boolean executeDocument (Project project) throws IOException {
		String code = project.getDocument();
		if (code == null || code.trim().isEmpty()) return false;
		HashMap<String, Object> parameters = new HashMap();
		parameters.put("project", project);
		try {
			Scar.executeCode(code, parameters, project);
		} catch (RuntimeException ex) {
			throw new RuntimeException("Error executing code for project: " + project, ex);
		}
		return true;
	}

	static public void main (String[] args) throws IOException {
		Scar.args = new Arguments(args);

		if (Scar.args.has("trace"))
			TRACE();
		else if (Scar.args.has("debug"))
			DEBUG();
		else if (Scar.args.has("info"))
			INFO();
		else if (Scar.args.has("warn"))
			WARN();
		else if (Scar.args.has("error")) //
			ERROR();

		Project project = project(Scar.args.get("file", "."));
		if (!executeDocument(project)) build(project);
	}
}
