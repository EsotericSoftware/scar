
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
import java.net.URI;
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

public class Scar {
	static public final String SCAR_HOME;
	static {
		if (System.getProperty("scar.home") != null)
			SCAR_HOME = System.getProperty("scar.home");
		else
			SCAR_HOME = System.getenv("SCAR_HOME");
	}
	static public final String JAVA_HOME = System.getProperty("java.home");

	static public final boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac os x");
	static public final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

	static {
		Paths.setDefaultGlobExcludes("**/.svn/**");
	}

	static public Project project (String dirOrYaml) throws IOException {
		if (dirOrYaml == null) throw new IllegalArgumentException("dirOrYaml cannot be null.");

		Project defaults = new Project();

		File file = new File(canonical(dirOrYaml));
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

		return project(dirOrYaml, defaults);
	}

	static public Project project (String dirOrYaml, Project defaults) throws IOException {
		if (dirOrYaml == null) throw new IllegalArgumentException("dirOrYaml cannot be null.");
		if (defaults == null) throw new IllegalArgumentException("defaults cannot be null.");

		Project project = new Project();
		project.merge(defaults);
		for (String include : project.getList("includes"))
			project.merge(project(include, defaults));
		project.merge(new Project(dirOrYaml));
		return project;
	}

	static public String resolvePath (String file) {
		if (file == null) return null;

		String foundFile;
		while (true) {
			foundFile = canonical(file);
			if (fileExists(foundFile)) break;

			foundFile = new File(SCAR_HOME, file).getPath();
			if (fileExists(foundFile)) break;

			foundFile = new File(JAVA_HOME, "bin/" + file).getPath();
			if (fileExists(foundFile)) break;

			foundFile = file;
			break;
		}
		if (TRACE) trace("scar", "Path \"" + file + "\" resolved to: " + foundFile);
		return foundFile;
	}

	static public void clean (Project project) {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "Clean: " + project);
		new Paths(project.format("{target}")).delete();
	}

	static public Paths classpath (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		Paths classpath = project.getPaths("classpath");
		classpath.add(dependencyClasspath(project));
		return classpath;
	}

	static public Paths dependencyClasspath (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		Paths paths = new Paths();
		for (String dependency : project.getList("dependencies")) {
			Project dependencyProject = project(dependency);
			String dependencyTarget = dependencyProject.format("{target}/");
			if (!fileExists(dependencyTarget)) throw new RuntimeException("Dependency has not been built: " + dependency);
			paths.add(new Paths(dependencyTarget, "*.jar"));
			paths.add(classpath(dependencyProject));
		}
		return paths;
	}

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

		ArrayList<String> args = new ArrayList();
		// args.add("-verbose");
		args.add("-nowarn");
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
		return classesDir;
	}

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

	static public void jar (String jarFile, Paths paths) throws IOException {
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
	}

	static public String dist (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");

		if (INFO) info("scar", "Dist: " + project);

		String distDir = mkdir(project.format("{target}/dist/"));
		classpath(project).copyTo(distDir);
		project.getPaths("dist").copyTo(distDir);
		new Paths(project.format("{target}"), "*.jar").copyTo(distDir);
		return distDir;
	}

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

	static public String createTempKeystore (String company, String title) throws IOException {
		String keystoreFile = File.createTempFile("jws", "keystore").getAbsolutePath();
		return createKeystore(keystoreFile, title, "password", company, title);
	}

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

	static public String normalize (String jarFile) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");

		if (DEBUG) debug("scar", "Normalizing JAR: " + jarFile);

		executeCommand("pack200", "--repack", jarFile);
		return jarFile;
	}

	static public String pack200 (String jarFile) throws IOException {
		String packedFile = pack200(jarFile, jarFile + ".pack");
		delete(jarFile);
		return packedFile;
	}

	static public String pack200 (String jarFile, String packedFile) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");

		if (DEBUG) debug("scar", "Packing JAR: " + jarFile + " -> " + packedFile);

		executeCommand("pack200", "--no-gzip", "--segment-limit=-1", "--no-keep-file-order", "--effort=7",
			"--modification-time=latest", packedFile, jarFile);
		return packedFile;
	}

	static public String unpack200 (String packedFile) throws IOException {
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");
		if (!packedFile.endsWith(".pack")) throw new IllegalArgumentException("packedFile must end with .pack: " + packedFile);

		String jarFile = unpack200(packedFile, substring(packedFile, 0, -5));
		delete(packedFile);
		return jarFile;
	}

	static public String unpack200 (String packedFile, String jarFile) throws IOException {
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");

		if (DEBUG) debug("scar", "Unpacking JAR: " + packedFile + " -> " + jarFile);

		executeCommand("unpack200", jarFile, packedFile);
		return jarFile;
	}

	static public String gzip (String file) throws IOException {
		String gzipFile = gzip(file, file + ".gz");
		delete(file);
		return gzipFile;
	}

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

	static public String ungzip (String gzipFile) throws IOException {
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");
		if (!gzipFile.endsWith(".gz")) throw new IllegalArgumentException("gzipFile must end with .gz: " + gzipFile);

		String file = ungzip(gzipFile, substring(gzipFile, 0, -3));
		delete(gzipFile);
		return file;
	}

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

	static public String unzip (String zipFile) throws IOException {
		if (zipFile == null) throw new IllegalArgumentException("zipFile cannot be null.");
		if (!zipFile.endsWith(".zip")) throw new IllegalArgumentException("zipFile must end with .zip: " + zipFile);

		String file = unzip(zipFile, substring(zipFile, 0, -4));
		delete(zipFile);
		return file;
	}

	static public String zip (Paths paths, String zipFile) throws IOException {
		if (paths == null) throw new IllegalArgumentException("paths cannot be null.");
		if (zipFile == null) throw new IllegalArgumentException("zipFile cannot be null.");

		if (DEBUG) debug("scar", "Creating ZIP (" + paths.count() + " entries): " + zipFile);

		paths.zip(zipFile);
		return zipFile;
	}

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

	static public String lzma (String file) throws IOException {
		String lzmaFile = lzma(file, file + ".lzma");
		delete(file);
		return lzmaFile;
	}

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

	static public String unlzma (String lzmaFile) throws IOException {
		if (lzmaFile == null) throw new IllegalArgumentException("lzmaFile cannot be null.");
		if (!lzmaFile.endsWith(".lzma")) throw new IllegalArgumentException("lzmaFile must end with .lzma: " + lzmaFile);

		String file = unlzma(lzmaFile, substring(lzmaFile, 0, -5));
		delete(lzmaFile);
		return file;
	}

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
	 * Creates a new, temporary keystore, assembles, signs, and optionally packs JARs. Should be called after
	 * {@link #dist(Project)}.
	 */
	static public void jws (Project project, boolean pack, String company, String title) throws IOException {
		String keystoreFile = createTempKeystore(company, title);
		jws(project, pack, keystoreFile, title, "password");
		delete(keystoreFile);
	}

	/**
	 * Assembles, signs, and optionally packs JARs. Should be called after {@link #dist(Project)}.
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
	 * Creates Apache .htaccess and var files for serving packed and unpacked JARs. Should be called after
	 * {@link #jws(Project, boolean, String, String, String)} or {@link #jws(Project, boolean, String, String)} (with packing).
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
	 * Creates a JNLP file. Should be called after {@link #jws(Project, boolean, String, String, String)} or
	 * {@link #jws(Project, boolean, String, String)}.
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
			writer.write("\t<information>\n");
			writer.write("\t\t<title>" + title + "</title>\n");
			writer.write("\t\t<vendor>" + company + "</vendor>\n");
			writer.write("\t\t<homepage href='" + domain + "'/>\n");
			writer.write("\t\t<description>" + title + "</description>\n");
			writer.write("\t\t<description kind='short'>" + title + "</description>\n");
			if (splashImage != null) writer.write("\t\t<icon kind='splash' href='" + path + splashImage + "'/>\n");
			writer.write("\t</information>\n");
			writer.write("\t<security>\n");
			writer.write("\t\t<all-permissions/>\n");
			writer.write("\t</security>\n");
			writer.write("\t<resources>\n");
			writer.write("\t\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");

			// JAR with main class first.
			String projectJarName;
			if (project.has("version"))
				projectJarName = project.format("{name}-{version}.jar");
			else
				projectJarName = project.format("{name}.jar");
			writer.write("\t\t<jar href='" + path + projectJarName + "'/>\n");

			// Rest of JARs, except natives.
			for (String file : new Paths(jwsDir, "**/*.jar", "!*natives*", "!**/" + projectJarName))
				writer.write("\t\t<jar href='" + path + fileName(file) + "'/>\n");

			writer.write("\t</resources>\n");
			if (fileExists(jwsDir + "natives-win.jar")) {
				writer.write("\t<resources os='Windows'>\n");
				writer.write("\t\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t\t<nativelib href='" + path + "natives-win.jar'/>\n");
				writer.write("\t</resources>\n");
			}
			if (fileExists(jwsDir + "natives-mac.jar")) {
				writer.write("\t<resources os='Mac'>\n");
				writer.write("\t\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t\t<nativelib href='" + path + "natives-mac.jar'/>\n");
				writer.write("\t</resources>\n");
			}
			if (fileExists(jwsDir + "natives-linux.jar")) {
				writer.write("\t<resources os='Linux'>\n");
				writer.write("\t\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t\t<nativelib href='" + path + "natives-linux.jar'/>\n");
				writer.write("\t</resources>\n");
			}
			if (fileExists(jwsDir + "natives-solaris.jar")) {
				writer.write("\t<resources os='SunOS'>\n");
				writer.write("\t\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t\t<nativelib href='" + path + "natives-solaris.jar'/>\n");
				writer.write("\t</resources>\n");
			}
			writer.write("\t<application-desc main-class='" + project.get("main") + "'/>\n");
			writer.write("</jnlp>");
		} finally {
			try {
				writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	static public void signLwjglApplet (String dir, String company, String title) throws IOException {
		String keystoreFile = createTempKeystore(company, title);
		signLwjglApplet(dir, keystoreFile, title, "password");
		delete(keystoreFile);
	}

	static public void signLwjglApplet (String dir, String keystoreFile, String alias, String password) throws IOException {
		if (dir == null) throw new IllegalArgumentException("dir cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		if (INFO) info("scar", "Signing LWJGL applet JARs: " + dir);

		for (String jarFile : new Paths(dir, "*.jar")) {
			sign(normalize(unsign(jarFile)), keystoreFile, alias, password);
			if (fileExists(jarFile + ".lzma")) lzma(jarFile, jarFile + ".lzma");
		}
		for (String jarLzmaFile : new Paths(dir, "*.jar.lzma")) {
			if (fileExists(substring(jarLzmaFile, 0, -5))) continue;
			String jarFile = sign(normalize(unsign(unlzma(jarLzmaFile))), keystoreFile, alias, password);
			lzma(jarFile, jarFile + ".lzma");
			lzma(pack200(jarFile));
		}
	}

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

	static public String moveFile (String in, String out) throws IOException {
		if (in == null) throw new IllegalArgumentException("in cannot be null.");
		if (out == null) throw new IllegalArgumentException("out cannot be null.");

		copyFile(in, out);
		delete(in);
		return out;
	}

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

	static public String mkdir (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		if (new File(path).mkdirs() && TRACE) trace("scar", "Created directory: " + path);
		return path;
	}

	static public boolean fileExists (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		return new File(path).exists();
	}

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

	static public String fileName (String path) {
		return new File(canonical(path)).getName();
	}

	static public String fileExtension (String file) {
		if (file == null) throw new IllegalArgumentException("fileName cannot be null.");

		int commaIndex = file.indexOf('.');
		if (commaIndex == -1) return "";
		return file.substring(commaIndex + 1);
	}

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

	static public String substring (String text, int start, int end) {
		if (text == null) throw new IllegalArgumentException("text cannot be null.");

		if (end >= 0) return text.substring(start, end);
		return text.substring(start, text.length() + end);
	}

	static public void executeCode (String code, HashMap<String, Object> parameters) {
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
			classBuffer.append("\n) {\n");
			// Append code, collecting imports.
			StringBuilder importBuffer = new StringBuilder(512);
			BufferedReader reader = new BufferedReader(new StringReader(code));
			while (true) {
				String line = reader.readLine();
				if (line == null) break;
				if (line.trim().startsWith("import ")) {
					importBuffer.append(line);
					importBuffer.append('\n');
				} else {
					classBuffer.append(line);
					classBuffer.append('\n');
				}
			}
			classBuffer.append("}}");

			final String classCode = importBuffer.append(classBuffer).toString();
			if (TRACE) trace("scar", "Executing code: " + classCode);

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
			Class containerClass = new ClassLoader() {
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
			throw new RuntimeException("Error executing code: " + code, ex);
		}
	}

	public static void main (String[] args) throws IOException {
		Project project = project(".");
		String code = project.getDocument();
		if (code != null && !code.trim().isEmpty()) {
			HashMap<String, Object> parameters = new HashMap();
			parameters.put("args", new Arguments(args));
			parameters.put("project", project);
			executeCode(code, parameters);
			return;
		}
		clean(project);
		compile(project);
		jar(project);
		dist(project);
	}
}
