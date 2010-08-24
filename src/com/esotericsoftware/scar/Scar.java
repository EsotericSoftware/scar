
package com.esotericsoftware.scar;

import static com.esotericsoftware.minlog.Log.*;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.tools.JavaFileObject.Kind;

import SevenZip.LzmaAlone;

import com.esotericsoftware.wildcard.Paths;

// BOZO - Add javadocs method and add javadocs to dist.

/**
 * Provides utility methods for common Java build tasks.
 */
public class Scar {
	/**
	 * The Scar installation directory. The value comes from the SCAR_HOME environment variable, if it exists. Alternatively, the
	 * "scar.home" System property can be defined.
	 */
	static public final String SCAR_HOME;
	static {
		if (System.getProperty("scar.home") != null)
			SCAR_HOME = System.getProperty("scar.home");
		else
			SCAR_HOME = System.getenv("SCAR_HOME");
	}

	/**
	 * The command line arguments Scar was started with. Empty if Scar was started with no arguments or Scar was not started from
	 * the command line.
	 */
	static public Arguments args = new Arguments();

	/**
	 * The Java installation directory.
	 */
	static public final String JAVA_HOME = System.getProperty("java.home");

	/**
	 * True if running on a Mac OS.
	 */
	static public final boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac os x");

	/**
	 * True if running on a Windows OS.
	 */
	static public final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

	static {
		Paths.setDefaultGlobExcludes("**/.svn/**");
	}

	/**
	 * Loads the specified project with default values and loads any other projects needed for the "include" property.
	 * @param path Path to a YAML project file, or a directory containing a "project.yaml" file.
	 */
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
		defaults.set("classpath", "lib|**/*.jar");
		defaults.set("dist", "dist");

		ArrayList source = new ArrayList();
		source.add("src|**/*.java");
		source.add("src/main/java|**/*.java");
		defaults.set("source", source);

		ArrayList resources = new ArrayList();
		resources.add("resources");
		resources.add("src/main/resources");
		defaults.set("resources", resources);

		Project project = project(path, defaults);

		// Remove dependency if a JAR of the same name is on the classpath.
		Paths classpath = project.getPaths("classpath");
		classpath.add(dependencyClasspaths(project, classpath, false));
		for (String dependency : project.getList("dependencies")) {
			String dependencyName = project(project.path(dependency)).get("name");
			for (String classpathFile : classpath) {
				String name = fileWithoutExtension(classpathFile);
				int dashIndex = name.lastIndexOf('-');
				if (dashIndex != -1) name = name.substring(0, dashIndex);
				if (name.equals(dependencyName)) {
					if (TRACE)
						trace("Ignoring " + project + " dependency: " + dependencyName + " (already on classpath: " + classpathFile
							+ ")");
					project.remove("dependencies", dependency);
					break;
				}
			}
		}

		return project;
	}

	/**
	 * Loads the specified project with the specified defaults and loads any other projects needed for the "include" property.
	 * @param path Path to a YAML project file, or a directory containing a "project.yaml" file.
	 */
	static public Project project (String path, Project defaults) throws IOException {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");
		if (defaults == null) throw new IllegalArgumentException("defaults cannot be null.");

		Project actualProject = new Project(path);

		Project project = new Project();
		project.replace(defaults);
		for (String include : actualProject.getList("include"))
			project.replace(project(actualProject.path(include), defaults));
		project.replace(actualProject);
		return project;
	}

	/**
	 * Returns the full path for the specified file name in the current working directory, the {@link #SCAR_HOME}, and the bin
	 * directory of {@link #JAVA_HOME}.
	 */
	static public String resolvePath (String fileName) {
		if (fileName == null) return null;

		String foundFile;
		while (true) {
			foundFile = canonical(fileName);
			if (fileExists(foundFile)) break;

			foundFile = new File(SCAR_HOME, fileName).getPath();
			if (fileExists(foundFile)) break;

			foundFile = new File(JAVA_HOME, "bin/" + fileName).getPath();
			if (fileExists(foundFile)) break;

			foundFile = fileName;
			break;
		}
		if (TRACE) trace("scar", "Path \"" + fileName + "\" resolved to: " + foundFile);
		return foundFile;
	}

	/**
	 * Deletes the "target" directory and all files and directories under it.
	 */
	static public void clean (Project project) {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "Clean: " + project);
		new Paths(project.format("{target}")).delete();
	}

	/**
	 * Computes the classpath for the specified project and all its dependency projects, recursively.
	 */
	static public Paths classpath (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		Paths classpath = project.getPaths("classpath");
		classpath.add(dependencyClasspaths(project, classpath, true));
		return classpath;
	}

	/**
	 * Computes the classpath for all the dependencies of the specified project, recursively.
	 */
	static private Paths dependencyClasspaths (Project project, Paths paths, boolean includeDependencyJAR) throws IOException {
		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(project.path(dependency));
			String dependencyTarget = dependencyProject.format("{target}/");
			if (!fileExists(dependencyTarget)) throw new RuntimeException("Dependency has not been built: " + dependency);
			if (includeDependencyJAR) paths.glob(dependencyTarget, "*.jar");
			paths.add(classpath(dependencyProject));
		}
		return paths;
	}

	/**
	 * Collects the source files using the "source" property and compiles them into a "classes" directory under the target
	 * directory. It uses "classpath" and "dependencies" to find the libraries required to compile the source.
	 * <p>
	 * Note: Each dependency project is not built automatically. Each needs to be built before the dependent project.
	 * @return The path to the "classes" directory.
	 */
	static public String compile (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		Paths classpath = classpath(project);
		Paths source = project.getPaths("source");

		if (INFO) info("scar", "Compile: " + project);
		if (DEBUG) {
			debug("scar", "Source: " + source.count() + " files");
			debug("scar", "Classpath: " + classpath);
		}

		String classesDir = mkdir(project.format("{target}/classes/"));
		File tempFile = File.createTempFile("scar", "compile");

		ArrayList<String> args = new ArrayList();
		if (TRACE) args.add("-verbose");
		args.add("-d");
		args.add(classesDir);
		args.add("-g:source,lines");
		args.add("-target");
		args.add("1.5");
		args.addAll(source.getPaths());
		if (!classpath.isEmpty()) {
			args.add("-classpath");
			args.add(classpath.toString(";"));
		}

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
			throw new RuntimeException("No compiler available. Ensure you are running from a 1.6+ JDK, and not a JRE.");
		if (compiler.run(null, null, null, args.toArray(new String[args.size()])) != 0) {
			throw new RuntimeException("Error during compilation of project: " + project.get("name") + "\nSource: " + source.count()
				+ " files\nClasspath: " + classpath);
		}
		try {
			Thread.sleep(100);
		} catch (InterruptedException ex) {
		}
		return classesDir;
	}

	/**
	 * Collects the class files from the "classes" directory and all the resource files using the "resources" property and encodes
	 * them into a JAR file.
	 * 
	 * If the resources don't contain a META-INF/MANIFEST.MF file, one is generated. If the project has a main property, the
	 * generated manifest will include "Main-Class" and "Class-Path" entries to allow the main class to be run with "java -jar".
	 * @return The path to the created JAR file.
	 */
	static public String jar (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "JAR: " + project);

		String jarDir = mkdir(project.format("{target}/jar/"));

		String classesDir = project.format("{target}/classes/");
		new Paths(classesDir, "**/*.class").copyTo(jarDir);
		project.getPaths("resources").copyTo(jarDir);

		String jarFile;
		if (project.has("version"))
			jarFile = project.format("{target}/{name}-{version}.jar");
		else
			jarFile = project.format("{target}/{name}.jar");

		File manifestFile = new File(jarDir, "META-INF/MANIFEST.MF");
		if (!manifestFile.exists()) {
			if (DEBUG) debug("scar", "Generating JAR manifest: " + manifestFile);
			mkdir(manifestFile.getParent());
			Manifest manifest = new Manifest();
			Attributes attributes = manifest.getMainAttributes();
			attributes.putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
			if (project.has("main")) {
				if (DEBUG) debug("scar", "Main class: " + project.get("main"));
				attributes.putValue(Attributes.Name.MAIN_CLASS.toString(), project.get("main"));
				StringBuilder buffer = new StringBuilder(512);
				buffer.append(fileName(jarFile));
				buffer.append(" .");
				Paths classpath = classpath(project);
				for (String name : classpath.getNames()) {
					buffer.append(' ');
					buffer.append(name);
				}
				attributes.putValue(Attributes.Name.CLASS_PATH.toString(), buffer.toString());
			}
			FileOutputStream output = new FileOutputStream(manifestFile);
			try {
				manifest.write(output);
			} finally {
				try {
					output.close();
				} catch (Exception ignored) {
				}
			}
		}

		jar(jarFile, new Paths(jarDir));
		return jarFile;
	}

	/**
	 * Encodes the specified paths into a JAR file.
	 * @return The path to the JAR file.
	 */
	static public String jar (String jarFile, Paths paths) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (paths == null) throw new IllegalArgumentException("paths cannot be null.");

		paths = paths.filesOnly();

		if (DEBUG) debug("scar", "Creating JAR (" + paths.count() + " entries): " + jarFile);

		List<String> fullPaths = paths.getPaths();
		List<String> relativePaths = paths.getRelativePaths();
		// Ensure MANIFEST.MF is first.
		int manifestIndex = relativePaths.indexOf("META-INF/MANIFEST.MF");
		if (manifestIndex > 0) {
			relativePaths.remove(manifestIndex);
			relativePaths.add(0, "META-INF/MANIFEST.MF");
			String manifestFullPath = fullPaths.get(manifestIndex);
			fullPaths.remove(manifestIndex);
			fullPaths.add(0, manifestFullPath);
		}
		JarOutputStream output = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)));
		try {
			for (int i = 0, n = fullPaths.size(); i < n; i++) {
				output.putNextEntry(new JarEntry(relativePaths.get(i).replace('\\', '/')));
				FileInputStream input = new FileInputStream(fullPaths.get(i));
				try {
					byte[] buffer = new byte[4096];
					while (true) {
						int length = input.read(buffer);
						if (length == -1) break;
						output.write(buffer, 0, length);
					}
				} finally {
					try {
						input.close();
					} catch (Exception ignored) {
					}
				}
			}
		} finally {
			try {
				output.close();
			} catch (Exception ignored) {
			}
		}
		return jarFile;
	}

	/**
	 * Collects the distribution files using the "dist" property, the project's JAR file, and everything on the project's classpath
	 * (including dependency project classpaths) and places them into a "dist" directory under the "target" directory. This is also
	 * done for depenency projects, recursively. This is everything the application needs to be run from JAR files.
	 * @return The path to the "dist" directory.
	 */
	static public String dist (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "Dist: " + project);

		String distDir = mkdir(project.format("{target}/dist/"));
		classpath(project).copyTo(distDir);
		Paths distPaths = project.getPaths("dist");
		dependencyDistPaths(project, distPaths);
		distPaths.copyTo(distDir);
		new Paths(project.format("{target}"), "*.jar").copyTo(distDir);
		return distDir;
	}

	static private Paths dependencyDistPaths (Project project, Paths paths) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(project.path(dependency));
			String dependencyTarget = dependencyProject.format("{target}/");
			if (!fileExists(dependencyTarget)) throw new RuntimeException("Dependency has not been built: " + dependency);
			paths.glob(dependencyTarget + "dist");
			paths.add(dependencyDistPaths(dependencyProject, paths));
		}
		return paths;
	}

	/**
	 * Removes any code signatures on the specified JAR. Removes any signature files in the META-INF directory and removes any
	 * signature entries from the JAR's manifest.
	 * @return The path to the JAR file.
	 */
	static public String unsign (String jarFile) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");

		if (DEBUG) debug("scar", "Removing signature from JAR: " + jarFile);

		File tempFile = File.createTempFile("scar", "removejarsig");
		JarOutputStream jarOutput = null;
		JarInputStream jarInput = null;
		try {
			jarOutput = new JarOutputStream(new FileOutputStream(tempFile));
			jarInput = new JarInputStream(new FileInputStream(jarFile));
			Manifest manifest = jarInput.getManifest();
			if (manifest != null) {
				// Remove manifest file entries.
				manifest.getEntries().clear();
				jarOutput.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
				manifest.write(jarOutput);
			}
			byte[] buffer = new byte[4096];
			while (true) {
				JarEntry entry = jarInput.getNextJarEntry();
				if (entry == null) break;
				String name = entry.getName();
				// Skip signature files.
				if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")))
					continue;
				jarOutput.putNextEntry(new JarEntry(name));
				while (true) {
					int length = jarInput.read(buffer);
					if (length == -1) break;
					jarOutput.write(buffer, 0, length);
				}
			}
			jarInput.close();
			jarOutput.close();
			copyFile(tempFile.getAbsolutePath(), jarFile);
		} finally {
			try {
				if (jarInput != null) jarInput.close();
			} catch (Exception ignored) {
			}
			try {
				if (jarOutput != null) jarOutput.close();
			} catch (Exception ignored) {
			}
			tempFile.delete();
		}
		return jarFile;
	}

	/**
	 * Creates a new keystore in the temp directory for signing JARs. The title is used for the alias and "password" is used for
	 * the password.
	 * @return The path to the keystore file.
	 */
	static public String createTempKeystore (String company, String title) throws IOException {
		String keystoreFile = File.createTempFile("jws", "keystore").getAbsolutePath();
		return createKeystore(keystoreFile, title, "password", company, title);
	}

	/**
	 * Creates a new keystore for signing JARs.
	 * @return The path to the keystore file.
	 */
	static public String createKeystore (String keystoreFile, String alias, String password, String company, String title)
		throws IOException {
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");
		if (company == null) throw new IllegalArgumentException("company cannot be null.");
		if (title == null) throw new IllegalArgumentException("title cannot be null.");

		if (DEBUG)
			debug("scar", "Creating keystore (" + alias + ":" + password + ", " + company + ", " + title + "): " + keystoreFile);

		File file = new File(keystoreFile);
		file.delete();
		Process process = Runtime.getRuntime().exec(
			new String[] {resolvePath("keytool"), "-genkey", "-keystore", keystoreFile, "-alias", alias});
		OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream());
		writer.write(password + "\n"); // Enter keystore password:
		writer.write(password + "\n"); // Re-enter new password:
		writer.write(company + "\n"); // What is your first and last name?
		writer.write(title + "\n"); // What is the name of your organizational unit?
		writer.write(title + "\n"); // What is the name of your organization?
		writer.write("\n"); // What is the name of your City or Locality? [Unknown]
		writer.write("\n"); // What is the name of your State or Province? [Unknown]
		writer.write("\n"); // What is the two-letter country code for this unit? [Unknown]
		writer.write("yes\n"); // Correct?
		writer.write("\n"); // Return if same alias key password as keystore.
		writer.flush();
		process.getOutputStream().close();
		process.getInputStream().close();
		process.getErrorStream().close();
		try {
			process.waitFor();
		} catch (InterruptedException ignored) {
		}
		if (!file.exists()) throw new RuntimeException("Error creating keystore.");
		return keystoreFile;
	}

	/**
	 * Signs the specified JAR.
	 * @return The path to the JAR.
	 */
	static public String sign (String jarFile, String keystoreFile, String alias, String password) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		if (DEBUG) debug("scar", "Signing JAR (" + keystoreFile + ", " + alias + ":" + password + "): " + jarFile);

		executeCommand("jarsigner", "-keystore", keystoreFile, "-storepass", password, "-keypass", password, jarFile, alias);
		return jarFile;
	}

	/**
	 * Packs and unpacks the JAR using pack200. This normalizes the JAR file structure so that it can be signed and then packed
	 * without invalidating the signature.
	 * @return The path to the JAR.
	 */
	static public String normalize (String jarFile) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");

		if (DEBUG) debug("scar", "Normalizing JAR: " + jarFile);

		unpack200(pack200(jarFile));
		return jarFile;
	}

	/**
	 * Encodes the specified file with pack200. The resulting filename is the filename plus ".pack". The file is deleted after
	 * encoding.
	 * @return The path to the encoded file.
	 */
	static public String pack200 (String jarFile) throws IOException {
		String packedFile = pack200(jarFile, jarFile + ".pack");
		delete(jarFile);
		return packedFile;
	}

	/**
	 * Encodes the specified file with pack200.
	 * @return The path to the encoded file.
	 */
	static public String pack200 (String jarFile, String packedFile) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");

		if (DEBUG) debug("scar", "Packing JAR: " + jarFile + " -> " + packedFile);

		executeCommand("pack200", "--no-gzip", "--segment-limit=-1", "--no-keep-file-order", "--effort=7",
			"--modification-time=latest", packedFile, jarFile);
		return packedFile;
	}

	/**
	 * Decodes the specified file with pack200. The filename must end in ".pack" and the resulting filename has this stripped. The
	 * encoded file is deleted after decoding.
	 * @return The path to the decoded file.
	 */
	static public String unpack200 (String packedFile) throws IOException {
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");
		if (!packedFile.endsWith(".pack")) throw new IllegalArgumentException("packedFile must end with .pack: " + packedFile);

		String jarFile = unpack200(packedFile, substring(packedFile, 0, -5));
		delete(packedFile);
		return jarFile;
	}

	/**
	 * Decodes the specified file with pack200.
	 * @return The path to the decoded file.
	 */
	static public String unpack200 (String packedFile, String jarFile) throws IOException {
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");

		if (DEBUG) debug("scar", "Unpacking JAR: " + packedFile + " -> " + jarFile);

		executeCommand("unpack200", packedFile, jarFile);
		return jarFile;
	}

	/**
	 * Encodes the specified file with GZIP. The resulting filename is the filename plus ".gz". The file is deleted after encoding.
	 * @return The path to the encoded file.
	 */
	static public String gzip (String file) throws IOException {
		String gzipFile = gzip(file, file + ".gz");
		delete(file);
		return gzipFile;
	}

	/**
	 * Encodes the specified file with GZIP.
	 * @return The path to the encoded file.
	 */
	static public String gzip (String file, String gzipFile) throws IOException {
		if (file == null) throw new IllegalArgumentException("file cannot be null.");
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");

		if (DEBUG) debug("scar", "GZIP encoding: " + file + " -> " + gzipFile);

		InputStream input = new FileInputStream(file);
		try {
			copyStream(input, new GZIPOutputStream(new FileOutputStream(gzipFile)));
		} finally {
			try {
				input.close();
			} catch (Exception ignored) {
			}
		}
		return gzipFile;
	}

	/**
	 * Decodes the specified GZIP file. The filename must end in ".gz" and the resulting filename has this stripped. The encoded
	 * file is deleted after decoding.
	 * @return The path to the decoded file.
	 */
	static public String ungzip (String gzipFile) throws IOException {
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");
		if (!gzipFile.endsWith(".gz")) throw new IllegalArgumentException("gzipFile must end with .gz: " + gzipFile);

		String file = ungzip(gzipFile, substring(gzipFile, 0, -3));
		delete(gzipFile);
		return file;
	}

	/**
	 * Decodes the specified GZIP file.
	 * @return The path to the decoded file.
	 */
	static public String ungzip (String gzipFile, String file) throws IOException {
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");
		if (file == null) throw new IllegalArgumentException("file cannot be null.");

		if (DEBUG) debug("scar", "GZIP decoding: " + gzipFile + " -> " + file);

		InputStream input = new GZIPInputStream(new FileInputStream(gzipFile));
		try {
			copyStream(input, new FileOutputStream(file));
		} finally {
			try {
				input.close();
			} catch (Exception ignored) {
			}
		}
		return file;
	}

	/**
	 * Encodes the specified files with ZIP.
	 * @return The path to the encoded file.
	 */
	static public String zip (Paths paths, String zipFile) throws IOException {
		if (paths == null) throw new IllegalArgumentException("paths cannot be null.");
		if (zipFile == null) throw new IllegalArgumentException("zipFile cannot be null.");

		if (DEBUG) debug("scar", "Creating ZIP (" + paths.count() + " entries): " + zipFile);

		paths.zip(zipFile);
		return zipFile;
	}

	/**
	 * Decodes the specified ZIP file.
	 * @return The path to the output directory.
	 */
	static public String unzip (String zipFile, String outputDir) throws IOException {
		if (zipFile == null) throw new IllegalArgumentException("zipFile cannot be null.");
		if (outputDir == null) throw new IllegalArgumentException("outputDir cannot be null.");

		if (DEBUG) debug("scar", "ZIP decoding: " + zipFile + " -> " + outputDir);

		ZipInputStream input = new ZipInputStream(new FileInputStream(zipFile));
		try {
			while (true) {
				ZipEntry entry = input.getNextEntry();
				if (entry == null) break;
				File file = new File(outputDir, entry.getName());
				if (entry.isDirectory()) {
					mkdir(file.getPath());
					continue;
				}
				mkdir(file.getParent());
				FileOutputStream output = new FileOutputStream(file);
				try {
					byte[] buffer = new byte[4096];
					while (true) {
						int length = input.read(buffer);
						if (length == -1) break;
						output.write(buffer, 0, length);
					}
				} finally {
					try {
						output.close();
					} catch (Exception ignored) {
					}
				}
			}
		} finally {
			try {
				input.close();
			} catch (Exception ignored) {
			}
		}
		return outputDir;
	}

	/**
	 * Encodes the specified file with LZMA. The resulting filename is the filename plus ".lzma". The file is deleted after
	 * encoding.
	 * @return The path to the encoded file.
	 */
	static public String lzma (String file) throws IOException {
		String lzmaFile = lzma(file, file + ".lzma");
		delete(file);
		return lzmaFile;
	}

	/**
	 * Encodes the specified file with LZMA.
	 * @return The path to the encoded file.
	 */
	static public String lzma (String file, String lzmaFile) throws IOException {
		if (file == null) throw new IllegalArgumentException("file cannot be null.");
		if (lzmaFile == null) throw new IllegalArgumentException("lzmaFile cannot be null.");

		if (DEBUG) debug("scar", "LZMA encoding: " + file + " -> " + lzmaFile);

		try {
			LzmaAlone.main(new String[] {"e", file, lzmaFile});
		} catch (Exception ex) {
			throw new IOException("Error lzma compressing file: " + file, ex);
		}
		return lzmaFile;
	}

	/**
	 * Decodes the specified LZMA file. The filename must end in ".lzma" and the resulting filename has this stripped. The encoded
	 * file is deleted after decoding.
	 * @return The path to the decoded file.
	 */
	static public String unlzma (String lzmaFile) throws IOException {
		if (lzmaFile == null) throw new IllegalArgumentException("lzmaFile cannot be null.");
		if (!lzmaFile.endsWith(".lzma")) throw new IllegalArgumentException("lzmaFile must end with .lzma: " + lzmaFile);

		String file = unlzma(lzmaFile, substring(lzmaFile, 0, -5));
		delete(lzmaFile);
		return file;
	}

	/**
	 * Decodes the specified LZMA file.
	 * @return The path to the decoded file.
	 */
	static public String unlzma (String lzmaFile, String file) throws IOException {
		if (lzmaFile == null) throw new IllegalArgumentException("lzmaFile cannot be null.");
		if (file == null) throw new IllegalArgumentException("file cannot be null.");

		if (DEBUG) debug("scar", "LZMA decoding: " + lzmaFile + " -> " + file);

		try {
			LzmaAlone.main(new String[] {"d", lzmaFile, file});
		} catch (Exception ex) {
			throw new IOException("Error lzma decompressing file: " + file, ex);
		}
		return file;
	}

	/**
	 * Same as {@link #jws(Project, boolean, String, String, String)}, but uses a {@link #createTempKeystore(String, String)
	 * temporary keystore}.
	 */
	static public void jws (Project project, boolean pack, String company, String title) throws IOException {
		String keystoreFile = createTempKeystore(company, title);
		jws(project, pack, keystoreFile, title, "password");
		delete(keystoreFile);
	}

	/**
	 * Copies all the JAR and JNLP files from the "dist" directory to a "jws" directory under the "target" directory. It then
	 * creates a temporary keystore and signs each JAR. If the "pack" parameter is true, it also compresses each JAR using pack200
	 * and GZIP.
	 */
	static public void jws (Project project, boolean pack, String keystoreFile, String alias, String password) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		if (INFO) info("scar", "JWS: " + project);

		String jwsDir = mkdir(project.format("{target}/jws/"));
		String distDir = project.format("{target}/dist/");
		new Paths(distDir, "*.jar", "*.jnlp").copyTo(jwsDir);
		for (String file : new Paths(jwsDir, "*.jar"))
			sign(normalize(file), keystoreFile, alias, password);
		if (pack) {
			String unpackedDir = mkdir(jwsDir + "unpacked/");
			String packedDir = mkdir(jwsDir + "packed/");
			for (String file : new Paths(jwsDir, "*.jar", "!*natives*")) {
				String fileName = fileName(file);
				String unpackedFile = unpackedDir + fileName;
				moveFile(file, unpackedFile);
				String packedFile = packedDir + fileName;
				gzip(pack200(copyFile(unpackedFile, packedFile)));
			}
		}
	}

	/**
	 * Generates ".htaccess" and "type map" VAR files in the "jws" directory. These files allow Apache to serve both pack200/GZIP
	 * JARs and regular JARs, based on capability of the client requesting the JAR.
	 */
	static public void jwsHtaccess (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "JWS htaccess: " + project);

		String jwsDir = mkdir(project.format("{target}/jws/"));
		for (String packedFile : new Paths(jwsDir + "packed", "*.jar.pack.gz")) {
			String packedFileName = fileName(packedFile);
			String jarFileName = substring(packedFileName, 0, -8);
			FileWriter writer = new FileWriter(jwsDir + jarFileName + ".var");
			try {
				writer.write("URI: packed/" + packedFileName + "\n");
				writer.write("Content-Type: x-java-archive\n");
				writer.write("Content-Encoding: pack200-gzip\n");
				writer.write("URI: unpacked/" + jarFileName + "\n");
				writer.write("Content-Type: x-java-archive\n");
			} finally {
				try {
					writer.close();
				} catch (Exception ignored) {
				}
			}
		}
		FileWriter writer = new FileWriter(jwsDir + ".htaccess");
		try {
			writer.write("AddType application/x-java-jnlp-file .jnlp"); // JNLP mime type.
			writer.write("AddType application/x-java-archive .jar\n"); // JAR mime type.
			writer.write("AddHandler application/x-type-map .var\n"); // Enable type maps.
			writer.write("Options +MultiViews\n");
			writer.write("MultiViewsMatch Any\n"); // Apache 2.0 only.
			writer.write("<Files *.pack.gz>\n");
			writer.write("AddEncoding pack200-gzip .jar\n"); // Enable Content-Encoding header for .jar.pack.gz files.
			writer.write("RemoveEncoding .gz\n"); // Prevent mod_gzip from messing with the Content-Encoding response.
			writer.write("</Files>\n");
		} finally {
			try {
				writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Generates a JNLP file in the "jws" directory. JARs in the "jws" directory are included in the JNLP. JARs containing "native"
	 * and "win", "mac", "linux", or "solaris" are properly included in the native section of the JNLP. The "main" property is used
	 * for the main class in the JNLP.
	 * @param splashImage Can be null.
	 */
	static public void jnlp (Project project, String url, String company, String title, String splashImage) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		if (url == null) throw new IllegalArgumentException("url cannot be null.");
		if (!url.startsWith("http")) throw new RuntimeException("Invalid url: " + url);
		if (company == null) throw new IllegalArgumentException("company cannot be null.");
		if (title == null) throw new IllegalArgumentException("title cannot be null.");

		if (DEBUG)
			debug("scar", "JNLP: " + project + " (" + url + ", " + company + ", " + title + ", " + splashImage + ")");
		else if (INFO) //
			info("scar", "JNLP: " + project);

		if (!project.has("main")) throw new RuntimeException("Unable to generate JNLP: project has no main class");

		int firstSlash = url.indexOf("/", 7);
		int lastSlash = url.lastIndexOf("/");
		if (firstSlash == -1 || lastSlash == -1) throw new RuntimeException("Invalid url: " + url);
		String domain = url.substring(0, firstSlash + 1);
		String path = url.substring(firstSlash + 1, lastSlash + 1);
		String jnlpFile = url.substring(lastSlash + 1);

		String jwsDir = mkdir(project.format("{target}/jws/"));
		FileWriter writer = new FileWriter(jwsDir + jnlpFile);
		try {
			writer.write("<?xml version='1.0' encoding='utf-8'?>\n");
			writer.write("<jnlp spec='1.0+' codebase='" + domain + "' href='" + path + jnlpFile + "'>\n");
			writer.write("<information>\n");
			writer.write("\t<title>" + title + "</title>\n");
			writer.write("\t<vendor>" + company + "</vendor>\n");
			writer.write("\t<homepage href='" + domain + "'/>\n");
			writer.write("\t<description>" + title + "</description>\n");
			writer.write("\t<description kind='short'>" + title + "</description>\n");
			if (splashImage != null) writer.write("\t<icon kind='splash' href='" + path + splashImage + "'/>\n");
			writer.write("</information>\n");
			writer.write("<security>\n");
			writer.write("\t<all-permissions/>\n");
			writer.write("</security>\n");
			writer.write("<resources>\n");
			writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");

			// JAR with main class first.
			String projectJarName;
			if (project.has("version"))
				projectJarName = project.format("{name}-{version}.jar");
			else
				projectJarName = project.format("{name}.jar");
			writer.write("\t<jar href='" + path + projectJarName + "'/>\n");

			// Rest of JARs, except natives.
			for (String file : new Paths(jwsDir, "**/*.jar", "!*natives*", "!**/" + projectJarName))
				writer.write("\t<jar href='" + path + fileName(file) + "'/>\n");

			writer.write("</resources>\n");
			Paths nativePaths = new Paths(jwsDir, "*native*win*", "*win*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='Windows'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + nativePaths.getNames().get(0) + "natives-win.jar'/>\n");
				writer.write("</resources>\n");
			}
			nativePaths = new Paths(jwsDir, "*native*mac*", "*mac*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='Mac'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + nativePaths.getNames().get(0) + "natives-mac.jar'/>\n");
				writer.write("</resources>\n");
			}
			nativePaths = new Paths(jwsDir, "*native*linux*", "*mac*linux*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='Linux'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + nativePaths.getNames().get(0) + "natives-linux.jar'/>\n");
				writer.write("</resources>\n");
			}
			nativePaths = new Paths(jwsDir, "*native*solaris*", "*solaris*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='SunOS'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + nativePaths.getNames().get(0) + "natives-solaris.jar'/>\n");
				writer.write("</resources>\n");
			}
			writer.write("<application-desc main-class='" + project.get("main") + "'/>\n");
			writer.write("</jnlp>");
		} finally {
			try {
				writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Same as {@link #lwjglApplet(Project, String, String, String)} but uses a {@link #createTempKeystore(String, String)
	 * temporary keystore}.
	 */
	static public void lwjglApplet (Project project, String company, String title) throws IOException {
		String keystoreFile = createTempKeystore(company, title);
		lwjglApplet(project, keystoreFile, title, "password");
		delete(keystoreFile);
	}

	static public void lwjglApplet (Project project, String keystoreFile, String alias, String password) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		if (INFO) info("scar", "LWJGL applet: " + project);

		String appletDir = mkdir(project.format("{target}/applet-lwjgl/"));
		String distDir = project.format("{target}/dist/");
		new Paths(distDir, "*.jar", "*.html", "*.htm").copyTo(appletDir);
		for (String jarFile : new Paths(appletDir, "*.jar")) {
			sign(normalize(unsign(jarFile)), keystoreFile, alias, password);
			String fileName = fileName(jarFile);
			if (fileName.equals("lwjgl_util_applet.jar") || fileName.equals("lzma.jar")) continue;
			lzma(jarFile, jarFile + ".lzma");
			if (fileName.contains("natives")) continue;
			lzma(pack200(jarFile));
		}

		if (!new Paths(appletDir, "*.html", "*.htm").isEmpty()) return;
		if (!project.has("main")) {
			if (DEBUG) debug("Unable to generate applet.html: project has no main class");
			return;
		}
		if (INFO) info("scar", "Generating: applet.html");
		FileWriter writer = new FileWriter(appletDir + "applet.html");
		try {
			writer.write("<html>\n");
			writer.write("<head><title>Applet</title></head>\n");
			writer.write("<body>\n");
			writer
				.write("<applet code='org.lwjgl.util.applet.AppletLoader' archive='lwjgl_util_applet.jar, lzma.jar' codebase='.' width='640' height='480'>\n");
			writer.write("<param name='al_title' value='" + project + "'>\n");
			writer.write("<param name='al_main' value='" + project.get("main") + "'>\n");
			writer.write("<param name='al_jars' value='");
			int i = 0;
			for (String name : new Paths(appletDir, "*.jar.pack.lzma").getNames()) {
				if (i++ > 0) writer.write(", ");
				writer.write(name);
			}
			writer.write("'>\n");
			Paths nativePaths = new Paths(appletDir, "*native*win*.jar.lzma", "*mac*win*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_windows' value='" + nativePaths.getNames().get(0) + "'>\n");
			nativePaths = new Paths(appletDir, "*native*mac*.jar.lzma", "*mac*mac*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_mac' value='" + nativePaths.getNames().get(0) + "'>\n");
			nativePaths = new Paths(appletDir, "*native*linux*.jar.lzma", "*mac*linux*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_linux' value='" + nativePaths.getNames().get(0) + "'>\n");
			nativePaths = new Paths(appletDir, "*native*solaris*.jar.lzma", "*mac*solaris*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_solaris' value='" + nativePaths.getNames().get(0) + "'>\n");
			writer.write("<param name='al_logo' value='appletlogo.png'>\n");
			writer.write("<param name='al_progressbar' value='appletprogress.gif'>\n");
			writer.write("<param name='separate_jvm' value='true'>\n");
			writer.write("</applet>\n");
			writer.write("</body></html>\n");
		} finally {
			try {
				writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Unzips all JARs in the "dist" directory and replaces them all with a single JAR. The manifest from the project's JAR is
	 * used. Putting everything into a single JAR makes it harder to see what libraries are being used, but makes it easier for end
	 * users to distribute the application.
	 * <p>
	 * Note: Files with the same path in different JARs will be overwritten. Files in the project's JAR will never be overwritten,
	 * but may overwrite other files.
	 */
	static public void oneJAR (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "One JAR: " + project);

		String onejarDir = mkdir(project.format("{target}/onejar/"));
		String distDir = project.format("{target}/dist/");
		String projectJarName;
		if (project.has("version"))
			projectJarName = project.format("{name}-{version}.jar");
		else
			projectJarName = project.format("{name}.jar");
		for (String jarFile : new Paths(distDir, "*.jar", "!" + projectJarName))
			unzip(jarFile, onejarDir);
		unzip(distDir + projectJarName, onejarDir);
		new Paths(distDir, "*.jar").delete();
		jar(distDir + projectJarName, new Paths(onejarDir));
	}

	/**
	 * Executes the specified shell command. {@link #resolvePath(String)} is used to locate the file to execute. If not found, on
	 * Windows the same filename with an "exe" extension is also tried.
	 */
	static public void executeCommand (String... command) throws IOException {
		if (command == null) throw new IllegalArgumentException("command cannot be null.");
		if (command.length == 0) throw new IllegalArgumentException("command cannot be empty.");

		String originalCommand = command[0];
		command[0] = resolvePath(command[0]);
		if (!fileExists(command[0]) && isWindows) {
			command[0] = resolvePath(command[0] + ".exe");
			if (!fileExists(command[0])) command[0] = originalCommand;
		}

		if (TRACE) {
			StringBuilder buffer = new StringBuilder(256);
			for (String text : command) {
				buffer.append(text);
				buffer.append(' ');
			}
			trace("scar", "Executing command: " + buffer);
		}

		Process process = Runtime.getRuntime().exec(command);
		try {
			process.waitFor();
		} catch (InterruptedException ignored) {
		}
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		while (true) {
			String line = reader.readLine();
			if (line == null) break;
			System.out.println(line);
		}
		reader.close();
		reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
		while (true) {
			String line = reader.readLine();
			if (line == null) break;
			System.out.println(line);
		}
		if (process.exitValue() != 0) {
			StringBuilder buffer = new StringBuilder(256);
			for (String text : command) {
				buffer.append(text);
				buffer.append(' ');
			}
			throw new RuntimeException("Error executing command: " + buffer);
		}
	}

	/**
	 * Reads to the end of the input stream and writes the bytes to the output stream.
	 */
	static public void copyStream (InputStream input, OutputStream output) throws IOException {
		if (input == null) throw new IllegalArgumentException("input cannot be null.");
		if (output == null) throw new IllegalArgumentException("output cannot be null.");

		try {
			byte[] buffer = new byte[4096];
			while (true) {
				int length = input.read(buffer);
				if (length == -1) break;
				output.write(buffer, 0, length);
			}
		} finally {
			try {
				output.close();
			} catch (Exception ignored) {
			}
			try {
				input.close();
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Copies a file, overwriting any existing file at the destination.
	 */
	static public String copyFile (String in, String out) throws IOException {
		if (in == null) throw new IllegalArgumentException("in cannot be null.");
		if (out == null) throw new IllegalArgumentException("out cannot be null.");

		if (TRACE) trace("scar", "Copying file: " + in + " -> " + out);

		FileChannel sourceChannel = null;
		FileChannel destinationChannel = null;
		try {
			sourceChannel = new FileInputStream(in).getChannel();
			destinationChannel = new FileOutputStream(out).getChannel();
			sourceChannel.transferTo(0, sourceChannel.size(), destinationChannel);
		} finally {
			try {
				if (sourceChannel != null) sourceChannel.close();
			} catch (Exception ignored) {
			}
			try {
				if (destinationChannel != null) destinationChannel.close();
			} catch (Exception ignored) {
			}
		}
		return out;
	}

	/**
	 * Moves a file, overwriting any existing file at the destination.
	 */
	static public String moveFile (String in, String out) throws IOException {
		if (in == null) throw new IllegalArgumentException("in cannot be null.");
		if (out == null) throw new IllegalArgumentException("out cannot be null.");

		copyFile(in, out);
		delete(in);
		return out;
	}

	/**
	 * Deletes a file or directory and all files and subdirecties under it.
	 */
	static public boolean delete (String fileName) {
		if (fileName == null) throw new IllegalArgumentException("fileName cannot be null.");

		File file = new File(fileName);
		if (file.exists() && file.isDirectory()) {
			File[] files = file.listFiles();
			for (int i = 0, n = files.length; i < n; i++) {
				if (files[i].isDirectory())
					delete(files[i].getAbsolutePath());
				else {
					if (TRACE) trace("scar", "Deleting file: " + files[i]);
					files[i].delete();
				}
			}
		}
		if (TRACE) trace("scar", "Deleting file: " + file);
		return file.delete();
	}

	/**
	 * Creates the directories in the specified path.
	 */
	static public String mkdir (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		if (new File(path).mkdirs() && TRACE) trace("scar", "Created directory: " + path);
		return path;
	}

	/**
	 * Returns true if the file exists.
	 */
	static public boolean fileExists (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		return new File(path).exists();
	}

	/**
	 * Returns the canonical path for the specified path. Eg, if "." is passed, this will resolve the actual path and return it.
	 */
	static public String canonical (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		File file = new File(path);
		try {
			return file.getCanonicalPath();
		} catch (IOException ex) {
			file = file.getAbsoluteFile();
			if (file.getName().equals(".")) file = file.getParentFile();
			return file.getPath();
		}
	}

	/**
	 * Returns only the filename portion of the specified path.
	 */
	static public String fileName (String path) {
		return new File(canonical(path)).getName();
	}

	/**
	 * Returns only the extension portion of the specified path, or an empty string if there is no extension.
	 */
	static public String fileExtension (String file) {
		if (file == null) throw new IllegalArgumentException("fileName cannot be null.");

		int commaIndex = file.indexOf('.');
		if (commaIndex == -1) return "";
		return file.substring(commaIndex + 1);
	}

	/**
	 * Returns only the filename portion of the specified path, with the extension, if any.
	 */
	static public String fileWithoutExtension (String file) {
		if (file == null) throw new IllegalArgumentException("fileName cannot be null.");

		int commaIndex = file.indexOf('.');
		if (commaIndex == -1) commaIndex = file.length();
		int slashIndex = file.replace('\\', '/').lastIndexOf('/');
		if (slashIndex == -1)
			slashIndex = 0;
		else
			slashIndex++;
		return file.substring(slashIndex, commaIndex);
	}

	/**
	 * Returns a substring of the specified text.
	 * @param end The end index of the substring. If negative, the index used will be "text.length() + end".
	 */
	static public String substring (String text, int start, int end) {
		if (text == null) throw new IllegalArgumentException("text cannot be null.");

		if (end >= 0) return text.substring(start, end);
		return text.substring(start, text.length() + end);
	}

	/**
	 * Compiles and executes the specified Java code. The code is compiled as if it were a Java method body.
	 * <p>
	 * Imports statements can be used at the start of the code. These imports are automatically used:<br>
	 * import com.esotericsoftware.scar.Scar;<br>
	 * import com.esotericsoftware.wildcard.Paths;<br>
	 * import com.esotericsoftware.minlog.Log;<br>
	 * import static com.esotericsoftware.scar.Scar.*;<br>
	 * import static com.esotericsoftware.minlog.Log.*;<br>
	 * <p>
	 * Entries can be added to the classpath by using "classpath [url];" statements at the start of the code. These classpath
	 * entries are checked before the classloader that loaded the Scar class is checked. Examples:<br>
	 * classpath someTools.jar;<br>
	 * classpath some/directory/of/class/files;<br>
	 * classpath http://example.com/someTools.jar;<br>
	 * @param parameters These parameters will be available in the scope where the code is executed.
	 */
	static public void executeCode (Project project, String code, HashMap<String, Object> parameters) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
			throw new RuntimeException("No compiler available. Ensure you are running from a 1.6+ JDK, and not a JRE.");

		try {
			// Wrap code in a class.
			StringBuilder classBuffer = new StringBuilder(2048);
			classBuffer.append("import com.esotericsoftware.scar.Scar;\n");
			classBuffer.append("import com.esotericsoftware.minlog.Log;\n");
			classBuffer.append("import com.esotericsoftware.wildcard.Paths;\n");
			classBuffer.append("import static com.esotericsoftware.scar.Scar.*;\n");
			classBuffer.append("import static com.esotericsoftware.minlog.Log.*;\n");
			classBuffer.append("public class Container {\n");
			classBuffer.append("public void execute (");
			int i = 0;
			for (Entry<String, Object> entry : parameters.entrySet()) {
				if (i++ > 0) classBuffer.append(',');
				classBuffer.append('\n');
				classBuffer.append(entry.getValue().getClass().getName());
				classBuffer.append(' ');
				classBuffer.append(entry.getKey());
			}
			classBuffer.append("\n) throws Exception {\n");

			// Append code, collecting imports statements and classpath URLs.
			StringBuilder importBuffer = new StringBuilder(512);
			ArrayList<URL> classpathURLs = new ArrayList();
			BufferedReader reader = new BufferedReader(new StringReader(code));
			boolean header = true;
			while (true) {
				String line = reader.readLine();
				if (line == null) break;
				String trimmed = line.trim();
				if (header && trimmed.startsWith("import ") && trimmed.endsWith(";")) {
					importBuffer.append(line);
					importBuffer.append('\n');
				} else if (header && trimmed.startsWith("classpath ") && trimmed.endsWith(";")) {
					String path = substring(line.trim(), 10, -1);
					try {
						classpathURLs.add(new URL(path));
					} catch (MalformedURLException ex) {
						classpathURLs.add(new File(project.path(path)).toURI().toURL());
					}
				} else {
					if (trimmed.length() > 0) header = false;
					classBuffer.append(line);
					classBuffer.append('\n');
				}
			}
			classBuffer.append("}}");

			final String classCode = importBuffer.append(classBuffer).toString();
			if (TRACE) trace("scar", "Executing code:\n" + classCode);

			// Compile class.
			final ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
			final SimpleJavaFileObject javaObject = new SimpleJavaFileObject(URI.create("Container.java"), Kind.SOURCE) {
				public OutputStream openOutputStream () {
					return output;
				}

				public CharSequence getCharContent (boolean ignoreEncodingErrors) {
					return classCode;
				}
			};
			compiler.getTask(null, new ForwardingJavaFileManager(compiler.getStandardFileManager(null, null, null)) {
				public JavaFileObject getJavaFileForOutput (Location location, String className, Kind kind, FileObject sibling) {
					return javaObject;
				}
			}, null, null, null, Arrays.asList(new JavaFileObject[] {javaObject})).call();

			// Load class.
			Class containerClass = new URLClassLoader(classpathURLs.toArray(new URL[classpathURLs.size()]), Scar.class
				.getClassLoader()) {
				protected synchronized Class<?> loadClass (String name, boolean resolve) throws ClassNotFoundException {
					// Look in this classloader before the parent.
					Class c = findLoadedClass(name);
					if (c == null) {
						try {
							c = findClass(name);
						} catch (ClassNotFoundException e) {
							return super.loadClass(name, resolve);
						}
					}
					if (resolve) resolveClass(c);
					return c;
				}

				protected Class<?> findClass (String name) throws ClassNotFoundException {
					if (name.equals("Container")) {
						byte[] bytes = output.toByteArray();
						return defineClass(name, bytes, 0, bytes.length);
					}
					return super.findClass(name);
				}
			}.loadClass("Container");

			// Execute.
			Class[] parameterTypes = new Class[parameters.size()];
			Object[] parameterValues = new Object[parameters.size()];
			i = 0;
			for (Object object : parameters.values()) {
				parameterValues[i] = object;
				parameterTypes[i++] = object.getClass();
			}
			containerClass.getMethod("execute", parameterTypes).invoke(containerClass.newInstance(), parameterValues);
		} catch (Exception ex) {
			throw new RuntimeException("Error executing code:\n" + code, ex);
		}
	}

	/**
	 * Calls {@link #build(Project)} for each dependency project in the specified project.
	 */
	static public void buildDependencies (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(project.path(dependency));

			String jarFile;
			if (dependencyProject.has("version"))
				jarFile = dependencyProject.format("{target}/{name}-{version}.jar");
			else
				jarFile = dependencyProject.format("{target}/{name}.jar");

			if (DEBUG) debug("Building dependency: " + dependencyProject);
			build(dependencyProject);
		}
	}

	/**
	 * Executes Java code in the specified project's document, or if there is no document it executes the buildDependencies, clean,
	 * compile, jar, and dist utility metshods.
	 */
	static public void build (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		String code = project.getDocument();
		if (code != null && !code.trim().isEmpty()) {
			HashMap<String, Object> parameters = new HashMap();
			parameters.put("project", project);
			executeCode(project, code, parameters);
			return;
		}
		buildDependencies(project);
		clean(project);
		compile(project);
		jar(project);
		dist(project);
	}

	private Scar () {
	}

	static public void main (String[] args) throws IOException {
		Scar.args = new Arguments(args);
		build(project(Scar.args.get("file", ".")));
	}
}
