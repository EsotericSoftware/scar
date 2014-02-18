
package com.esotericsoftware.scar;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.scar.Scar.*;

import com.esotericsoftware.wildcard.Paths;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

public class Jar {
	static private final String manifestFileName = "META-INF" + File.separator + "MANIFEST.MF";

	static public void jar (String outputFile, String inputDir) throws IOException {
		jar(outputFile, new Paths(inputDir), null, null);
	}

	static public void jar (String outputFile, String inputDir, String mainClass, Paths classpath) throws IOException {
		jar(outputFile, new Paths(inputDir), mainClass, classpath);
	}

	static public void jar (String outputFile, Paths inputPaths) throws IOException {
		jar(outputFile, inputPaths, null, null);
	}

	// BOZO - javadoc
	static public void jar (String outputFile, Paths inputPaths, String mainClass, Paths classpath) throws IOException {
		if (outputFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (inputPaths == null) throw new IllegalArgumentException("inputPaths cannot be null.");

		inputPaths = inputPaths.filesOnly();
		if (inputPaths.isEmpty()) {
			if (WARN) warn("scar", "No files to JAR.");
			return;
		}

		List<String> fullPaths = inputPaths.getPaths();
		List<String> relativePaths = inputPaths.getRelativePaths();
		int manifestIndex = relativePaths.indexOf(manifestFileName);
		if (manifestIndex > 0) {
			// Ensure MANIFEST.MF is first.
			relativePaths.remove(manifestIndex);
			relativePaths.add(0, manifestFileName);
			String manifestFullPath = fullPaths.get(manifestIndex);
			fullPaths.remove(manifestIndex);
			fullPaths.add(0, manifestFullPath);
		} else if (mainClass != null) {
			if (DEBUG) debug("scar", "Generating JAR manifest.");
			String manifestFile = tempFile("manifest");
			relativePaths.add(0, manifestFileName);
			fullPaths.add(0, manifestFile);

			Manifest manifest = new Manifest();
			Attributes attributes = manifest.getMainAttributes();
			attributes.putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
			if (DEBUG) debug("scar", "Main class: " + mainClass);
			attributes.putValue(Attributes.Name.MAIN_CLASS.toString(), mainClass);
			StringBuilder buffer = new StringBuilder(512);
			buffer.append(fileName(outputFile));
			buffer.append(" .");
			for (String name : classpath.getRelativePaths()) {
				buffer.append(' ');
				buffer.append(name);
			}
			attributes.putValue(Attributes.Name.CLASS_PATH.toString(), buffer.toString());
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

		if (DEBUG) debug("scar", "Creating JAR (" + inputPaths.count() + " entries): " + outputFile);

		mkdir(new File(outputFile).getParent());
		JarOutputStream output = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)));
		output.setLevel(Deflater.BEST_COMPRESSION);
		try {
			for (int i = 0, n = fullPaths.size(); i < n; i++) {
				JarEntry jarEntry = new JarEntry(relativePaths.get(i).replace('\\', '/'));
				output.putNextEntry(jarEntry);
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

	static public void oneJAR (String inputDir, String outputFile, String mainClass, Paths classpath) throws IOException {
		oneJAR(paths(inputDir, "*.jar"), outputFile, mainClass, classpath);
	}

	static public void oneJAR (Paths jars, String outputFile, String mainClass, Paths classpath) throws IOException {
		if (jars == null) throw new IllegalArgumentException("jars cannot be null.");

		String tempDir = tempDirectory("oneJAR");

		ArrayList<String> processedJARs = new ArrayList();
		for (String jarFile : jars) {
			unzip(jarFile, tempDir);
			processedJARs.add(jarFile);
		}

		if (mainClass != null) new File(tempDir, manifestFileName).delete();

		mkdir(parent(outputFile));
		jar(outputFile, tempDir, mainClass, classpath);
		delete(tempDir);
	}

	/** Removes any code signatures on the specified JAR. Removes any signature files in the META-INF directory and removes any
	 * signature entries from the JAR's manifest.
	 * @return The path to the JAR file. */
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
				jarOutput.putNextEntry(new JarEntry(manifestFileName));
				manifest.write(jarOutput);
			}
			byte[] buffer = new byte[4096];
			while (true) {
				JarEntry entry = jarInput.getNextJarEntry();
				if (entry == null) break;
				String name = entry.getName();
				// Skip signature files.
				if (name.startsWith("META-INF") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")))
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
		} catch (IOException ex) {
			throw new IOException("Error unsigning JAR file: " + jarFile, ex);
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

	/** Creates a new keystore for signing JARs. If the keystore file already exists, no action will be taken.
	 * @return The path to the keystore file. */
	static public String keystore (String keystoreFile, String alias, String password, String company, String title)
		throws IOException {
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (fileExists(keystoreFile)) return keystoreFile;
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

	/** Signs the specified JAR.
	 * @return The path to the JAR. */
	static public String sign (String jarFile, String keystoreFile, String alias, String password) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		if (DEBUG) debug("scar", "Signing JAR (" + keystoreFile + ", " + alias + ":" + password + "): " + jarFile);

		shell("jarsigner", "-keystore", keystoreFile, "-storepass", password, "-keypass", password, jarFile, alias);
		return jarFile;
	}

	/** Encodes the specified file with pack200. The resulting filename is the filename plus ".pack". The file is deleted after
	 * encoding.
	 * @return The path to the encoded file. */
	static public String pack200 (String jarFile) throws IOException {
		String packedFile = pack200(jarFile, jarFile + ".pack");
		delete(jarFile);
		return packedFile;
	}

	/** Encodes the specified file with pack200.
	 * @return The path to the encoded file. */
	static public String pack200 (String jarFile, String packedFile) throws IOException {
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");

		if (DEBUG) debug("scar", "Packing JAR: " + jarFile + " -> " + packedFile);

		shell("pack200", "--no-gzip", "--segment-limit=-1", "--no-keep-file-order", "--effort=7", "--modification-time=latest",
			packedFile, jarFile);
		return packedFile;
	}

	/** Decodes the specified file with pack200. The filename must end in ".pack" and the resulting filename has this stripped. The
	 * encoded file is deleted after decoding.
	 * @return The path to the decoded file. */
	static public String unpack200 (String packedFile) throws IOException {
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");
		if (!packedFile.endsWith(".pack")) throw new IllegalArgumentException("packedFile must end with .pack: " + packedFile);

		String jarFile = unpack200(packedFile, substring(packedFile, 0, -5));
		delete(packedFile);
		return jarFile;
	}

	/** Decodes the specified file with pack200.
	 * @return The path to the decoded file. */
	static public String unpack200 (String packedFile, String jarFile) throws IOException {
		if (packedFile == null) throw new IllegalArgumentException("packedFile cannot be null.");
		if (jarFile == null) throw new IllegalArgumentException("jarFile cannot be null.");

		if (DEBUG) debug("scar", "Unpacking JAR: " + packedFile + " -> " + jarFile);

		shell("unpack200", packedFile, jarFile);
		return jarFile;
	}

	static public ArrayList<String> entryNames (String jar) throws IOException {
		JarFile jarFile = new JarFile(jar);
		ArrayList<String> names = new ArrayList();
		for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements();)
			names.add(entries.nextElement().getName().replace('\\', '/'));
		jarFile.close();
		return names;
	}

	/** Combines the JARs into one. If both JARs have the same entry, the entry from the first JAR is used. */
	static public void mergeJars (String firstJar, String secondJar, String outJar) throws IOException {
		if (DEBUG) debug("scar", "Merging JARs: " + firstJar + " + " + secondJar + " -> " + outJar);

		JarFile firstJarFile = new JarFile(firstJar);
		JarFile secondJarFile = new JarFile(secondJar);

		HashSet<String> names = new HashSet();
		for (Enumeration<JarEntry> entries = firstJarFile.entries(); entries.hasMoreElements();)
			names.add(entries.nextElement().getName().replace('\\', '/'));
		for (Enumeration<JarEntry> entries = secondJarFile.entries(); entries.hasMoreElements();)
			names.add(entries.nextElement().getName().replace('\\', '/'));

		mkdir(parent(outJar));
		JarOutputStream outJarStream = new JarOutputStream(new FileOutputStream(outJar));
		for (String name : names) {
			InputStream input;
			ZipEntry entry = firstJarFile.getEntry(name);
			if (entry != null)
				input = firstJarFile.getInputStream(entry);
			else {
				entry = firstJarFile.getEntry(name.replace('/', '\\'));
				if (entry != null)
					input = firstJarFile.getInputStream(entry);
				else {
					entry = secondJarFile.getEntry(name);
					input = secondJarFile.getInputStream(entry != null ? entry : secondJarFile.getEntry(name.replace('/', '\\')));
				}
			}
			outJarStream.putNextEntry(new JarEntry(name));
			copyStream(input, outJarStream);
			outJarStream.closeEntry();
		}
		firstJarFile.close();
		secondJarFile.close();
		outJarStream.close();
	}

	static public void copyFromJAR (String inJar, String outJar, String... regexs) throws IOException {
		if (DEBUG) debug("scar", "Copying from JAR: " + inJar + " -> " + outJar + ", " + Arrays.asList(regexs));

		JarFile inJarFile = new JarFile(inJar);
		mkdir(parent(outJar));
		JarOutputStream outJarStream = new JarOutputStream(new FileOutputStream(outJar));
		for (Enumeration<JarEntry> entries = inJarFile.entries(); entries.hasMoreElements();) {
			JarEntry inEntry = entries.nextElement();
			String name = inEntry.getName();
			boolean matches = false;
			for (String regex : regexs) {
				if (name.matches(regex)) {
					matches = true;
					break;
				}
			}
			if (!matches) continue;

			JarEntry outEntry = new JarEntry(name);
			outJarStream.putNextEntry(outEntry);
			copyStream(inJarFile.getInputStream(inEntry), outJarStream);
			outJarStream.closeEntry();
		}
		outJarStream.close();
		inJarFile.close();
	}

	static public void removeFromJAR (String inJar, String outJar, String... regexs) throws IOException {
		if (DEBUG) debug("scar", "Removing from JAR: " + inJar + " -> " + outJar + ", " + Arrays.asList(regexs));

		JarFile inJarFile = new JarFile(inJar);
		mkdir(parent(outJar));
		JarOutputStream outJarStream = new JarOutputStream(new FileOutputStream(outJar));
		outer:
		for (Enumeration<JarEntry> entries = inJarFile.entries(); entries.hasMoreElements();) {
			JarEntry inEntry = entries.nextElement();
			String name = inEntry.getName();
			for (String regex : regexs)
				if (name.matches(regex)) continue outer;

			JarEntry outEntry = new JarEntry(name);
			outEntry.setTime(1370273339); // Reset time.
			outJarStream.putNextEntry(outEntry);
			copyStream(inJarFile.getInputStream(inEntry), outJarStream);
			outJarStream.closeEntry();
		}
		outJarStream.close();
		inJarFile.close();
	}

	static public void addToJAR (String inJar, String outJar, String addName, byte[] bytes) throws IOException {
		if (DEBUG) debug("scar", "Adding to JAR: " + inJar + " -> " + outJar + ", " + addName);

		JarFile inJarFile = new JarFile(inJar);
		mkdir(parent(outJar));
		JarOutputStream outJarStream = new JarOutputStream(new FileOutputStream(outJar));
		ArrayList<String> names = new ArrayList();
		for (Enumeration<JarEntry> entries = inJarFile.entries(); entries.hasMoreElements();)
			names.add(entries.nextElement().getName());

		if (names.contains(addName.replace('\\', '/')) || names.contains(addName.replace('/', '\\')))
			throw new RuntimeException("JAR already has entry: " + addName);
		addName = addName.replace('\\', '/');
		names.add(addName);
		Collections.sort(names);

		if (names.remove("META-INF/MANIFEST.MF") || names.remove("META-INF\\MANIFEST.MF")) names.add(0, "META-INF/MANIFEST.MF");

		for (String name : names) {
			outJarStream.putNextEntry(new JarEntry(name.replace('\\', '/')));
			if (name.replace('\\', '/').equals(addName))
				outJarStream.write(bytes);
			else
				copyStream(inJarFile.getInputStream(inJarFile.getEntry(name)), outJarStream);
			outJarStream.closeEntry();
		}
		outJarStream.close();
		inJarFile.close();
	}

	static public void setEntryTime (String inJar, String outJar, long time) throws IOException {
		if (DEBUG) debug("scar", "Setting entry to for JAR: " + inJar + " -> " + outJar + ", " + time);

		JarFile inJarFile = new JarFile(inJar);
		mkdir(parent(outJar));
		JarOutputStream outJarStream = new JarOutputStream(new FileOutputStream(outJar));
		for (Enumeration<JarEntry> entries = inJarFile.entries(); entries.hasMoreElements();) {
			JarEntry inEntry = entries.nextElement();
			String name = inEntry.getName();
			JarEntry outEntry = new JarEntry(name);
			outEntry.setTime(time);
			outJarStream.putNextEntry(outEntry);
			copyStream(inJarFile.getInputStream(inEntry), outJarStream);
			outJarStream.closeEntry();
		}
		outJarStream.close();
		inJarFile.close();
	}

	static public void setClassVersions (String inJar, String outJar, int max, int min) throws IOException {
		if (DEBUG) debug("scar", "Setting class versions for JAR: " + inJar + " -> " + outJar + ", " + max + "." + min);

		JarFile inJarFile = new JarFile(inJar);
		mkdir(parent(outJar));
		JarOutputStream outJarStream = new JarOutputStream(new FileOutputStream(outJar));
		for (Enumeration<JarEntry> entries = inJarFile.entries(); entries.hasMoreElements();) {
			String name = entries.nextElement().getName();
			outJarStream.putNextEntry(new JarEntry(name));
			InputStream input = inJarFile.getInputStream(inJarFile.getEntry(name));
			if (name.endsWith(".class")) input = new ClassVersionStream(input, name, max, min);
			copyStream(input, outJarStream);
			outJarStream.closeEntry();
		}
		outJarStream.close();
		inJarFile.close();
	}

	static class ClassVersionStream extends FilterInputStream {
		private boolean first = true;
		private final int max, min;
		private String name;

		public ClassVersionStream (InputStream in, String name, int max, int min) {
			super(in);
			this.name = name;
			this.max = max;
			this.min = min;
		}

		public int read (byte[] b, int off, int len) throws IOException {
			int count = super.read(b, off, len);
			if (first) {
				first = false;
				if (count < 8) throw new RuntimeException("Too few bytes: " + count);
				int oldMin = (b[off + 4] << 8) | b[off + 5];
				int oldMax = ((b[off + 6] & 0xff) << 8) | (b[off + 7] & 0xff);
				b[off + 4] = (byte)(min >> 8);
				b[off + 5] = (byte)min;
				b[off + 6] = (byte)(max >> 8);
				b[off + 7] = (byte)max;
				if (DEBUG && (oldMax != max || oldMin != min)) debug(oldMax + "." + oldMin + " to " + max + "." + min + ": " + name);
			}
			return count;
		}
	}
}
