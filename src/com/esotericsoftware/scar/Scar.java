
package com.esotericsoftware.scar;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.scar.Jar.*;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.commons.net.ftp.FTPClient;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import com.esotericsoftware.wildcard.Paths;

import SevenZip.LzmaAlone;

// BOZO - Add javadocs method.

/** Provides utility methods for common Java build tasks. */
public class Scar {
	/** The Scar installation directory. The value comes from the SCAR_HOME environment variable, if it exists. Alternatively, the
	 * "scar.home" System property can be defined. */
	static public final String SCAR_HOME;

	static {
		if (System.getProperty("scar.home") != null)
			SCAR_HOME = System.getProperty("scar.home");
		else
			SCAR_HOME = System.getenv("SCAR_HOME");
	}

	/** The command line arguments Scar was started with. Empty if Scar was started with no arguments or Scar was not started from
	 * the command line. */
	static public Arguments args = new Arguments();

	/** The Java installation directory. */
	static public final String JAVA_HOME = System.getProperty("java.home");

	/** True if running on a Mac OS. */
	static public final boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac os x");

	/** True if running on a Windows OS. */
	static public final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

	static {
		Paths.setDefaultGlobExcludes("**/.svn/**");
	}

	/** Returns the full path for the specified file name in the current working directory, the {@link #SCAR_HOME}, and the bin
	 * directory of {@link #JAVA_HOME}. */
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

	/** Encodes the specified file with GZIP. The resulting filename is the filename plus ".gz". The file is deleted after
	 * encoding.
	 * @return The path to the encoded file. */
	static public String gzip (String file) throws IOException {
		String gzipFile = gzip(file, file + ".gz");
		delete(file);
		return gzipFile;
	}

	/** Encodes the specified file with GZIP.
	 * @return The path to the encoded file. */
	static public String gzip (String file, String gzipFile) throws IOException {
		if (file == null) throw new IllegalArgumentException("file cannot be null.");
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");

		if (DEBUG) debug("scar", "GZIP encoding: " + file + " -> " + gzipFile);

		GZIPOutputStream output = new GZIPOutputStream(new FileOutputStream(gzipFile));
		try {
			copyStream(new FileInputStream(file), output);
		} finally {
			try {
				output.close();
			} catch (Exception ignored) {
			}
		}
		return gzipFile;
	}

	/** Decodes the specified GZIP file. The filename must end in ".gz" and the resulting filename has this stripped. The encoded
	 * file is deleted after decoding.
	 * @return The path to the decoded file. */
	static public String ungzip (String gzipFile) throws IOException {
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");
		if (!gzipFile.endsWith(".gz")) throw new IllegalArgumentException("gzipFile must end with .gz: " + gzipFile);

		String file = ungzip(gzipFile, substring(gzipFile, 0, -3));
		delete(gzipFile);
		return file;
	}

	/** Decodes the specified GZIP file.
	 * @return The path to the decoded file. */
	static public String ungzip (String gzipFile, String file) throws IOException {
		if (gzipFile == null) throw new IllegalArgumentException("gzipFile cannot be null.");
		if (file == null) throw new IllegalArgumentException("file cannot be null.");

		if (DEBUG) debug("scar", "GZIP decoding: " + gzipFile + " -> " + file);

		FileOutputStream output = new FileOutputStream(file);
		try {
			copyStream(new GZIPInputStream(new FileInputStream(gzipFile)), output);
		} finally {
			try {
				output.close();
			} catch (Exception ignored) {
			}
		}
		return file;
	}

	/** Encodes the specified files with ZIP.
	 * @return The path to the encoded file. */
	static public String zip (Paths paths, String zipFile) throws IOException {
		if (paths == null) throw new IllegalArgumentException("paths cannot be null.");
		if (zipFile == null) throw new IllegalArgumentException("zipFile cannot be null.");

		if (DEBUG) debug("scar", "Creating ZIP (" + paths.count() + " entries): " + zipFile);

		paths.zip(zipFile);
		return zipFile;
	}

	/** Decodes the specified ZIP file.
	 * @return The path to the output directory. */
	static public String unzip (String zipFile, String outputDir) throws IOException {
		if (zipFile == null) throw new IllegalArgumentException("zipFile cannot be null.");
		if (outputDir == null) throw new IllegalArgumentException("outputDir cannot be null.");

		if (DEBUG) debug("scar", "ZIP decoding: " + zipFile + " -> " + outputDir);

		ZipInputStream input = new ZipInputStream(new FileInputStream(zipFile));
		try {
			byte[] buffer = new byte[1024 * 10];
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

	/** Encodes the specified file with LZMA. The resulting filename is the filename plus ".lzma". The file is deleted after
	 * encoding.
	 * @return The path to the encoded file. */
	static public String lzma (String file) throws IOException {
		String lzmaFile = lzma(file, file + ".lzma");
		delete(file);
		return lzmaFile;
	}

	/** Encodes the specified file with LZMA.
	 * @return The path to the encoded file. */
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

	/** Decodes the specified LZMA file. The filename must end in ".lzma" and the resulting filename has this stripped. The encoded
	 * file is deleted after decoding.
	 * @return The path to the decoded file. */
	static public String unlzma (String lzmaFile) throws IOException {
		if (lzmaFile == null) throw new IllegalArgumentException("lzmaFile cannot be null.");
		if (!lzmaFile.endsWith(".lzma")) throw new IllegalArgumentException("lzmaFile must end with .lzma: " + lzmaFile);

		String file = unlzma(lzmaFile, substring(lzmaFile, 0, -5));
		delete(lzmaFile);
		return file;
	}

	/** Decodes the specified LZMA file.
	 * @return The path to the decoded file. */
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

	static public String tempFile (String prefix) throws IOException {
		return File.createTempFile(prefix, null).getAbsolutePath();
	}

	static public String tempDirectory (String prefix) throws IOException {
		File file = File.createTempFile(prefix, null);
		if (!file.delete()) throw new IOException("Unable to delete temp file: " + file);
		if (!file.mkdir()) throw new IOException("Unable to create temp directory: " + file);
		return file.getAbsolutePath();
	}

	static public String shell (String command) throws IOException {
		return shell(null, command);
	}

	/** Splits the specified command at spaces that are not surrounded by quotes and passes the result to
	 * {@link #shell(Map, String...)}. */
	static public String shell (Map<? extends String, ? extends String> env, String command) throws IOException {
		List<String> matchList = new ArrayList<String>();
		Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
		Matcher regexMatcher = regex.matcher(command);
		while (regexMatcher.find()) {
			if (regexMatcher.group(1) != null)
				matchList.add(regexMatcher.group(1));
			else if (regexMatcher.group(2) != null)
				matchList.add(regexMatcher.group(2));
			else
				matchList.add(regexMatcher.group());
		}
		return shell(env, matchList.toArray(new String[matchList.size()]));
	}

	static public String shell (String... command) throws IOException {
		return shell(null, command);
	}

	/** Executes the specified shell command with the specified environment variables. {@link #resolvePath(String)} is used to
	 * locate the file to execute. If not found, on Windows the same filename with an "exe" extension is also tried.
	 * @param env May be null. */
	static public String shell (Map<? extends String, ? extends String> env, String... command) throws IOException {
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
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		if (env != null) {
			builder.environment().putAll(env);
		}
		final Process process = builder.start();
		final StringBuilder outputBuffer = new StringBuilder(512);
		new Thread("shell") {
			public void run () {
				BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
				try {
					while (true) {
						String line = reader.readLine();
						if (line == null) break;
						if (INFO && (line.length() > 0 || outputBuffer.length() > 0)) info("scar", line);
						outputBuffer.append(line);
						outputBuffer.append('\n');
					}
					reader.close();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}.start();

		try {
			process.waitFor();
		} catch (InterruptedException ignored) {
		}
		if (process.exitValue() != 0) {
			StringBuilder buffer = new StringBuilder(256);
			for (String text : command) {
				buffer.append(text);
				buffer.append(' ');
			}
			throw new RuntimeException("Error executing command: " + buffer);
		}
		return outputBuffer.toString();
	}

	/** Reads to the end of the input stream and writes the bytes to the output stream. The input stream is closed, the output is
	 * not. */
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
				input.close();
			} catch (Exception ignored) {
			}
		}
	}

	/** Copies a file, overwriting any existing file at the destination. */
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
		} catch (IOException ex) {
			throw new IOException("Error copying: " + in + "\nTo: " + out, ex);
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

	/** Moves a file, overwriting any existing file at the destination. */
	static public String moveFile (String in, String out) throws IOException {
		if (in == null) throw new IllegalArgumentException("in cannot be null.");
		if (out == null) throw new IllegalArgumentException("out cannot be null.");

		copyFile(in, out);
		delete(in);
		return out;
	}

	static public byte[] readBytes (String file) throws IOException {
		long fileSize = fileSize(file);
		ByteArrayOutputStream output = new ByteArrayOutputStream(fileSize == 0 ? 1024 : (int)fileSize);
		FileInputStream input = new FileInputStream(file);
		byte[] buffer = new byte[256];
		while (true) {
			int length = input.read(buffer);
			if (length == -1) break;
			output.write(buffer, 0, length);
		}
		return output.toByteArray();
	}

	static public String readString (String file) throws IOException {
		return readString(file, null);
	}

	static public String readString (String fileName, String charset) throws IOException {
		if (fileName == null) throw new IllegalArgumentException("file cannot be null.");
		File file = new File(fileName);
		int fileLength = (int)file.length();
		if (fileLength == 0) fileLength = 512;
		StringBuilder output = new StringBuilder(fileLength);
		InputStreamReader reader = null;
		try {
			if (charset == null)
				reader = new InputStreamReader(new FileInputStream(file));
			else
				reader = new InputStreamReader(new FileInputStream(file), charset);
			char[] buffer = new char[256];
			while (true) {
				int length = reader.read(buffer);
				if (length == -1) break;
				output.append(buffer, 0, length);
			}
		} catch (IOException ex) {
			throw new RuntimeException("Error reading layout file: " + fileName, ex);
		} finally {
			try {
				if (reader != null) reader.close();
			} catch (IOException ignored) {
			}
		}
		return output.toString();
	}

	static public String writeFile (String fileName, String contents, boolean append) {
		return writeFile(fileName, contents, append, null);
	}

	static public String writeFile (String fileName, String contents, boolean append, String charset) {
		Writer writer = null;
		try {
			File file = new File(fileName);
			FileOutputStream output = new FileOutputStream(file, append);
			if (charset == null)
				writer = new OutputStreamWriter(output);
			else
				writer = new OutputStreamWriter(output, charset);
			writer.write(contents);
			return file.getAbsolutePath();
		} catch (Exception ex) {
			throw new RuntimeException("Error writing file: " + fileName, ex);
		} finally {
			try {
				if (writer != null) writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	/** Deletes a file or directory and all files and subdirecties under it. */
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

	/** Creates the directories in the specified path. */
	static public String mkdir (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		if (new File(path).mkdirs() && TRACE) trace("scar", "Created directory: " + path);
		return path;
	}

	/** Returns true if the file exists. */
	static public boolean fileExists (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		return new File(path).exists();
	}

	static public long fileSize (String path) {
		if (path == null) throw new IllegalArgumentException("path cannot be null.");

		return new File(path).length();
	}

	/** Returns the canonical path for the specified path. Eg, if "." is passed, this will resolve the actual path and return
	 * it. */
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

	/** Returns only the filename portion of the specified path. */
	static public String fileName (String path) {
		return new File(canonical(path)).getName();
	}

	/** Returns only the last modified time of the specified file. */
	static public long fileLastModified (String path) {
		return new File(canonical(path)).lastModified();
	}

	/** Returns the parent directory of the specified path. */
	static public String parent (String path) {
		return new File(canonical(path)).getParent();
	}

	/** Returns only the extension portion of the specified path, or an empty string if there is no extension. */
	static public String fileExtension (String file) {
		if (file == null) throw new IllegalArgumentException("fileName cannot be null.");

		int commaIndex = file.indexOf('.');
		if (commaIndex == -1) return "";
		return file.substring(commaIndex + 1);
	}

	/** Returns only the filename portion of the specified path, without the extension, if any. */
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

	/** Shortcut for System.out.println. */
	static public void log (String text) {
		System.out.println(text);
	}

	/** Returns a substring of the specified text.
	 * @param end The end index of the substring. If negative, the index used will be "text.length() + end". */
	static public String substring (String text, int start, int end) {
		if (text == null) throw new IllegalArgumentException("text cannot be null.");

		if (end >= 0) return text.substring(start, end);
		return text.substring(start, text.length() + end);
	}

	static public void jws (String inputDir, String outputDir, boolean pack, String keystoreFile, String alias, String password)
		throws IOException {
		if (inputDir == null) throw new IllegalArgumentException("inputDir cannot be null.");
		if (outputDir == null) throw new IllegalArgumentException("outputDir cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		mkdir(outputDir);
		paths(inputDir, "*.jar", "*.jnlp").copyTo(outputDir);
		for (String file : paths(outputDir, "*.jar"))
			sign(unpack200(pack200(unsign(file))), keystoreFile, alias, password);
		if (!pack) return;
		for (String file : paths(outputDir, "*.jar", "!*native*"))
			gzip(pack200(file));
	}

	static public void jwsHtaccess (String jwsDir) throws IOException {
		for (String packedFile : paths(jwsDir + "packed", "*.jar.pack.gz")) {
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

	static public void jnlp (String inputDir, String mainClass, String mainClassJar, String url, String company, String title,
		String splashImage) throws IOException {
		if (mainClass == null) throw new IllegalArgumentException("mainClass cannot be null.");
		if (url == null) throw new IllegalArgumentException("url cannot be null.");
		if (title == null) throw new IllegalArgumentException("title cannot be null.");
		if (company == null) throw new IllegalArgumentException("company cannot be null.");

		int firstSlash = url.indexOf("/", 7);
		int lastSlash = url.lastIndexOf("/");
		if (firstSlash == -1 || lastSlash == -1) throw new RuntimeException("Invalid url: " + url);
		String domain = url.substring(0, firstSlash + 1);
		String path = url.substring(firstSlash + 1, lastSlash + 1);
		String jnlpFile = url.substring(lastSlash + 1);

		FileWriter writer = new FileWriter(inputDir + jnlpFile);
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
			writer.write("\t<jar href='" + path + mainClassJar + "'/>\n");

			// Rest of JARs, except natives.
			for (String file : paths(inputDir, "**/*.jar", "**/*.jar.pack.lzma", "!*native*", "!**/" + mainClassJar))
				writer.write("\t<jar href='" + path + fileName(file) + "'/>\n");

			writer.write("</resources>\n");
			Paths nativePaths = paths(inputDir, "*native*win*", "*win*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='Windows'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + path + nativePaths.getNames().get(0) + "'/>\n");
				writer.write("</resources>\n");
			}
			nativePaths = paths(inputDir, "*native*mac*", "*mac*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='Mac'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + path + nativePaths.getNames().get(0) + "'/>\n");
				writer.write("</resources>\n");
			}
			nativePaths = paths(inputDir, "*native*linux*", "*linux*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='Linux'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + path + nativePaths.getNames().get(0) + "'/>\n");
				writer.write("</resources>\n");
			}
			nativePaths = paths(inputDir, "*native*solaris*", "*solaris*native*");
			if (nativePaths.count() == 1) {
				writer.write("<resources os='SunOS'>\n");
				writer.write("\t<j2se href='http://java.sun.com/products/autodl/j2se' version='1.5+' max-heap-size='128m'/>\n");
				writer.write("\t<nativelib href='" + path + nativePaths.getNames().get(0) + "'/>\n");
				writer.write("</resources>\n");
			}
			writer.write("<application-desc main-class='" + mainClass + "'/>\n");
			writer.write("</jnlp>");
		} finally {
			try {
				writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	static public void lwjglApplet (String inputDir, String outputDir, String keystoreFile, String alias, String password)
		throws IOException {
		if (inputDir == null) throw new IllegalArgumentException("inputDir cannot be null.");
		if (outputDir == null) throw new IllegalArgumentException("outputDir cannot be null.");
		if (keystoreFile == null) throw new IllegalArgumentException("keystoreFile cannot be null.");
		if (alias == null) throw new IllegalArgumentException("alias cannot be null.");
		if (password == null) throw new IllegalArgumentException("password cannot be null.");
		if (password.length() < 6) throw new IllegalArgumentException("password must be 6 or more characters.");

		mkdir(outputDir);
		paths(inputDir, "**/*.jar", "*.html", "*.htm").flatten().copyTo(outputDir);
		for (String jarFile : paths(outputDir, "*.jar")) {
			sign(unpack200(pack200(unsign(jarFile))), keystoreFile, alias, password);
			String fileName = fileName(jarFile);
			if (fileName.equals("lwjgl_util_applet.jar") || fileName.equals("lzma.jar")) continue;
			if (fileName.contains("native"))
				lzma(jarFile);
			else
				lzma(pack200(jarFile));
		}
	}

	static public void lwjglAppletHtml (String inputDir, String mainClass) throws IOException {
		if (INFO) info("scar", "Generating: applet.html");
		FileWriter writer = new FileWriter(inputDir + "applet.html");
		try {
			writer.write("<html>\n");
			writer.write("<head><title>Applet</title></head>\n");
			writer.write("<body>\n");
			writer.write(
				"<applet code='org.lwjgl.util.applet.AppletLoader' archive='lwjgl_util_applet.jar, lzma.jar' codebase='.' width='640' height='480'>\n");
			writer.write("<param name='al_version' value='1.0'>\n");
			writer.write("<param name='al_title' value='applet'>\n");
			writer.write("<param name='al_main' value='" + mainClass + "'>\n");
			writer.write("<param name='al_jars' value='");
			int i = 0;
			HashSet<String> names = new HashSet();
			for (String name : paths(inputDir, "*.jar.pack.lzma").getNames()) {
				names.add(name);
				if (i++ > 0) writer.write(", ");
				writer.write(name);
			}
			for (String name : paths(inputDir, "*.jar").getNames()) {
				if (names.contains(name + ".pack.lzma")) continue;
				if (i++ > 0) writer.write(", ");
				writer.write(name + ".pack.lzma");
			}
			writer.write("'>\n");
			Paths nativePaths = paths(inputDir, "*native*win*.jar.lzma", "*win*native*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_windows' value='" + nativePaths.getNames().get(0) + "'>\n");
			nativePaths = paths(inputDir, "*native*mac*.jar.lzma", "*mac*native*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_mac' value='" + nativePaths.getNames().get(0) + "'>\n");
			nativePaths = paths(inputDir, "*native*linux*.jar.lzma", "*linux*native*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_linux' value='" + nativePaths.getNames().get(0) + "'>\n");
			nativePaths = paths(inputDir, "*native*solaris*.jar.lzma", "*solaris*native*.jar.lzma");
			if (nativePaths.count() == 1) writer.write("<param name='al_solaris' value='" + nativePaths.getNames().get(0) + "'>\n");
			writer.write("<param name='al_logo' value='appletlogo.png'>\n");
			writer.write("<param name='al_progressbar' value='appletprogress.gif'>\n");
			writer.write("<param name='separate_jvm' value='true'>\n");
			writer.write(
				"<param name='java_arguments' value='-Dsun.java2d.noddraw=true -Dsun.awt.noerasebackground=true -Dsun.java2d.d3d=false -Dsun.java2d.opengl=false -Dsun.java2d.pmoffscreen=false'>\n");
			writer.write("</applet>\n");
			writer.write("</body></html>\n");
		} finally {
			try {
				writer.close();
			} catch (Exception ignored) {
			}
		}
	}

	static public Paths path (String file) {
		return new Paths().addFile(file);
	}

	static public Paths path (String dir, String file) {
		return new Paths().add(dir, file);
	}

	static public Paths paths (String dir, String... patterns) {
		return new Paths(dir, patterns);
	}

	static public void compile (Paths source, Paths classpath, String outputDir, String targetVersion) {
		if (source.isEmpty()) {
			if (WARN) warn("scar", "No source files found.");
			return;
		}

		ArrayList<String> args = new ArrayList();
		if (TRACE) args.add("-verbose");
		args.add("-d");
		args.add(outputDir);
		args.add("-g:source,lines");
		args.add("-source");
		args.add(targetVersion);
		args.add("-target");
		args.add(targetVersion);
		args.add("-encoding");
		args.add("UTF-8");
// args.addAll(source.getPaths());
		if (classpath != null && !classpath.isEmpty()) {
			args.add("-classpath");
			args.add(isWindows ? classpath.toString(";") : classpath.toString(":"));
		}

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
			throw new RuntimeException("No compiler available. Ensure you are running from a 1.6+ JDK, and not a JRE.");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
		Iterable<? extends JavaFileObject> compilationUnits = fileManager
			.getJavaFileObjectsFromStrings(source.filesOnly().getPaths());
		JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, args, null, compilationUnits);

// JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		boolean s = task.call();
		try {
			fileManager.close();
		} catch (IOException ex) {
		}

// if (compiler.run(System.in, System.out, System.err, args.toArray(new String[args.size()])) != 0) {
		if (!s) {
			System.out.flush();
			System.err.flush();
			StringBuilder b = new StringBuilder();
			b.append("Compilation failed.\nSource: ").append(source.count()) //
				.append(" files\nClasspath:\n").append(classpath.toString("\n"));
			b.append("\nCompilation parameters: ");
			for (String a : args)
				b.append(a).append(' ');
			if (!diagnostics.getDiagnostics().isEmpty()) {
				Pattern pattern = Pattern.compile("(.*): error: (.*)", Pattern.DOTALL);
				boolean lastWasNote = false;
				for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
					Diagnostic.Kind kind = diagnostic.getKind();
					b.append(lastWasNote ? "\n" : "\n\n").append(diagnostic.getKind().name()).append(": ");
					lastWasNote = kind == Diagnostic.Kind.NOTE;

					String message = diagnostic.toString();
					if (message.toLowerCase().startsWith(diagnostic.getKind().name().toLowerCase() + ": "))
						message = message.substring(diagnostic.getKind().name().length() + 2);

					Matcher matcher = pattern.matcher(message);
					if (matcher.matches()) {
						String file = matcher.group(1);
						int slash = file.lastIndexOf('\\');
						if (slash == -1) slash = file.lastIndexOf('/');
						if (slash != -1) b.append(file.substring(slash + 1).replaceAll("\\.java(:\\d+)$", "$1")).append(": ");
						b.append(matcher.group(2)).append("\n  file: ").append(file);
					} else
						b.append(message);
				}
			}
			throw new RuntimeException(b.toString());
		}
		try {
			Thread.sleep(100);
		} catch (InterruptedException ex) {
		}
	}

	static public void executeCode (String code, HashMap<String, Object> parameters) {
		executeCode(code, parameters);
	}

	/** Compiles and executes the speFcified Java code. The code is compiled as if it were a Java method body.
	 * <p>
	 * Imports statements can be used before any code. These imports are automatically used:<br>
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
	 * If a project parameter is not null, non-absolute classpath entries will be relative to the project directory.
	 * @param parameters These parameters will be available in the scope where the code is executed. */
	static public void executeCode (String code, HashMap<String, Object> parameters, Project project) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
			throw new RuntimeException("No compiler available. Ensure you are running from a 1.6+ JDK, and not a JRE.");

		try {
			// Wrap code in a class.
			StringBuilder classBuffer = new StringBuilder(2048);
			classBuffer.append("import com.esotericsoftware.scar.*;\n");
			classBuffer.append("import com.esotericsoftware.minlog.Log;\n");
			classBuffer.append("import com.esotericsoftware.wildcard.Paths;\n");
			classBuffer.append("import static com.esotericsoftware.scar.Scar.*;\n");
			classBuffer.append("import static com.esotericsoftware.minlog.Log.*;\n");
			classBuffer.append("public class Generated {\n");
			int templateStartLines = 6;
			classBuffer.append("public void execute (");
			int i = 0;
			for (Entry<String, Object> entry : parameters.entrySet()) {
				if (i++ > 0) classBuffer.append(',');
				classBuffer.append('\n');
				templateStartLines++;
				classBuffer.append(entry.getValue().getClass().getName());
				classBuffer.append(' ');
				classBuffer.append(entry.getKey());
			}
			classBuffer.append("\n) throws Exception {\n");
			templateStartLines += 2;

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
						if (project != null) classpathURLs.add(new File(project.path(path)).toURI().toURL());
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

			// Construct classpath option.
			List<String> options = new ArrayList<String>();
			{
				StringBuffer buffer = new StringBuffer(System.getProperty("java.class.path"));
				String pathSeparator = System.getProperty("path.separator");
				for (URL url : classpathURLs) {
					buffer.append(pathSeparator);
					buffer.append(new File(url.toURI()).getCanonicalPath());
				}
				if (TRACE) trace("scar", "Using classpath: " + buffer);
				options.add("-classpath");
				options.add(buffer.toString());
			}

			// Compile class.
			final ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
			final SimpleJavaFileObject javaObject = new SimpleJavaFileObject(URI.create("Generated.java"),
				JavaFileObject.Kind.SOURCE) {
				public OutputStream openOutputStream () {
					return output;
				}

				public CharSequence getCharContent (boolean ignoreEncodingErrors) {
					return classCode;
				}
			};
			DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector();
			compiler.getTask(null, new ForwardingJavaFileManager(compiler.getStandardFileManager(null, null, null)) {
				public JavaFileObject getJavaFileForOutput (Location location, String className, JavaFileObject.Kind kind,
					FileObject sibling) {
					return javaObject;
				}
			}, diagnostics, options, null, Arrays.asList(new JavaFileObject[] {javaObject})).call();

			boolean error = false;
			for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
				if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
					error = true;
					break;
				}
			}
			if (error) {
				StringBuilder buffer = new StringBuilder(1024);
				for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
					if (buffer.length() > 0) buffer.append("\n");
					buffer.append("Line ");
					buffer.append(diagnostic.getLineNumber() - templateStartLines);
					buffer.append(": ");
					buffer.append(diagnostic.getMessage(null).replaceAll("^Generated.java:\\d+:\\d* ", ""));
				}
				throw new RuntimeException("Compilation errors:\n" + buffer);
			}

			// Load class.
			Class generatedClass = new URLClassLoader(classpathURLs.toArray(new URL[classpathURLs.size()]),
				Scar.class.getClassLoader()) {
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
					if (name.equals("Generated")) {
						byte[] bytes = output.toByteArray();
						return defineClass(name, bytes, 0, bytes.length);
					}
					return super.findClass(name);
				}
			}.loadClass("Generated");

			// Execute.
			Class[] parameterTypes = new Class[parameters.size()];
			Object[] parameterValues = new Object[parameters.size()];
			i = 0;
			for (Object object : parameters.values()) {
				parameterValues[i] = object;
				parameterTypes[i++] = object.getClass();
			}
			generatedClass.getMethod("execute", parameterTypes).invoke(generatedClass.newInstance(), parameterValues);
		} catch (Throwable ex) {
			throw new RuntimeException("Error executing code:\n" + code.trim(), ex);
		}
	}

	static public boolean ftpUpload (String server, String user, String password, String dir, Paths paths, boolean passive)
		throws IOException {
		FTPClient ftp = new FTPClient();
		InetAddress address = InetAddress.getByName(server);
		if (DEBUG) debug("scar", "Connecting to FTP server: " + address);
		ftp.connect(address);
		if (passive) ftp.enterLocalPassiveMode();
		if (!ftp.login(user, password)) {
			if (ERROR) error("scar", "FTP login failed for user: " + user);
			return false;
		}
		if (!ftp.changeWorkingDirectory(dir)) {
			if (ERROR) error("scar", "FTP directory change failed: " + dir);
			return false;
		}
		ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
		for (String path : paths) {
			if (INFO) info("scar", "FTP upload: " + path);
			BufferedInputStream input = new BufferedInputStream(new FileInputStream(path));
			try {
				ftp.storeFile(new File(path).getName(), input);
			} finally {
				try {
					input.close();
				} catch (Exception ignored) {
				}
			}
		}
		ftp.logout();
		ftp.disconnect();
		return true;
	}

	static public void sftpUpload (String server, int port, String user, String password, String keyFile, String dir, Paths paths)
		throws IOException {
		sftpUpload(server, port, user, password, keyFile, dir, paths, null, true);
	}

	static public void sftpUpload (String server, int port, String user, String password, String keyFile, String dir, Paths paths,
		final ProgressMonitor monitor, final boolean printProgress) throws IOException {
		sftpUpload( //
			server, port, user, password, keyFile, //
			null, 0, null, null, //
			dir, paths, null, printProgress);
	}

	/** Upload through an SSH tunnel using an intermediate server.
	 * <p>
	 * Note some users have reported needing to use IP addresses. */
	static public void sftpUpload ( //
		String server1, int port1, String user1, final String password1, String keyFile, //
		String server2, int port2, String user2, final String password2, //
		String dir, Paths paths, final ProgressMonitor monitor, final boolean printProgress) throws IOException {

		Session session1 = null, session2 = null, session = null;
		try {
			long total = 0;
			for (String path : paths)
				total += new File(path).length();

			if (TRACE) {
				JSch.setLogger(new com.jcraft.jsch.Logger() {
					public boolean isEnabled (int pLevel) {
						return true;
					}

					public void log (int level, String message) {
						trace("scar", message);
					}
				});
			}

			JSch jsch = new JSch(); // https://github.com/mwiede/jsch
			if (keyFile != null) jsch.addIdentity(keyFile);
			ChannelSftp channel = null;
			int retries = 0;
			boolean cd = true;
			for (String path : paths) {
				SftpFileUpload fileUpload = new SftpFileUpload(path, total, monitor, printProgress);
				if (INFO) info("scar", "SFTP upload: " + fileUpload.file.getName() + " -> " + dir);
				while (true) {
					// Connect.
					boolean first = true;
					try {
						if (session1 == null || !session1.isConnected() || (server2 != null && !session2.isConnected())) {
							if (session1 != null) session1.disconnect();
							if (session2 != null) session2.disconnect();
							channel = null;

							session1 = jsch.getSession(user1, server1, port1);
							if (keyFile == null) {
								session1.setPassword(password1);
								session1.setConfig("PreferredAuthentications", "password");
								session1.setConfig("PubkeyAuthentication", "no");
							}
							session1.setConfig("StrictHostKeyChecking", "no");
							session1.connect(8000);
							first = false;

							if (server2 != null) {
								int forwardPort = session1.setPortForwardingL(0, server2, port2);
								session2 = jsch.getSession(user2, "127.0.0.1", forwardPort);
								if (keyFile == null) {
									session2.setPassword(password2);
									session2.setConfig("PubkeyAuthentication", "no");
									session2.setConfig("PreferredAuthentications", "password");
								}
								session2.setConfig("StrictHostKeyChecking", "no");
								session2.setHostKeyAlias(server2);
								session2.connect(8000);
								session = session2;
							} else
								session = session1;
						}
						if (channel == null || !channel.isConnected()) {
							channel = (ChannelSftp)session.openChannel("sftp");
							channel.connect(8000);
							retries = 0;
							cd = true;
						}
					} catch (Exception ex) {
						if (TRACE) trace("scar", "Connection error.", ex);
						if (retries == 3) {
							if (first)
								throw new IOException("Unable to connect for SFTP upload: " + user1 + "@" + server1 + ":" + port1);
							else
								throw new IOException("Unable to connect for SFTP upload: " + user2 + "@" + server2 + ":" + port2);
						}
						if (retries++ == 0 && WARN) warn("scar", "Connecting...");
						try {
							Thread.sleep(250);
						} catch (Exception ignored) {
						}
						continue;
					}

					// Upload.
					try {
						if (cd) {
							fileUpload.cd(channel, dir);
							cd = false;
						}
						fileUpload.upload(channel);
						break;
					} catch (FileNotFoundException ex) {
						throw ex;
					} catch (Exception ex) {
						if (TRACE) trace("scar", "Error during upload.", ex);
						continue;
					}
				}
			}
		} catch (Exception ex) {
			throw new IOException(ex);
		} finally {
			if (session2 != null) session2.disconnect();
			if (session1 != null) session1.disconnect();
		}
	}

	static private class SftpFileUpload {
		final File file;
		final long total, fileLength, interval;
		long fileCount, lastCount, totalCount;
		final ProgressMonitor monitor;
		final boolean printProgress;

		final SftpProgressMonitor sftpMonitor = new SftpProgressMonitor() {
			public void init (int op, String source, String dest, long max) {
				if (!printProgress) return;
				System.out.print("|-");
				if (fileCount > 0) {
					lastCount = 0;
					while (fileCount - lastCount >= interval) {
						lastCount += interval;
						System.out.print("-");
					}
				}
			}

			public void end () {
				if (printProgress) System.out.println("|");
			}

			public boolean count (long c) {
				totalCount += c;
				fileCount += c;
				if (printProgress) {
					while (fileCount - lastCount >= interval) {
						lastCount += interval;
						System.out.print("-");
					}
				}
				if (monitor != null) monitor.progress((float)(fileCount / (double)fileLength), (float)(totalCount / (double)total));
				return true;
			}
		};

		SftpFileUpload (String path, long total, ProgressMonitor monitor, boolean printProgress) throws IOException {
			this.printProgress = printProgress;
			file = new File(path);
			this.total = total;
			this.monitor = monitor;
			fileLength = file.length();
			interval = Math.max(1, fileLength / 76);
		}

		void cd (ChannelSftp channel, String path) throws Exception {
			try {
				channel.cd(path);
			} catch (SftpException ex) {
				if (ex.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw new Exception("Error setting remote directory: " + path, ex);
				mkdirs(channel, path);
			}
		}

		private void mkdirs (ChannelSftp channel, String path) throws Exception {
			StringBuilder good = new StringBuilder(path.length());
			try {
				for (String dir : path.split("/")) {
					if (dir.isEmpty()) continue;
					if (good.length() == 0) dir = '/' + dir;
					good.append(dir);
					try {
						channel.cd(dir);
					} catch (SftpException ex) {
						if (ex.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw ex;
						channel.mkdir(dir);
						if (TRACE) trace("scar", "Created remote directory: " + good);
						channel.cd(dir);
					}
					good.append("/");
				}
			} catch (Exception ex) {
				throw new Exception("Error creating remote directory: " + good, ex);
			}
		}

		void upload (ChannelSftp channel) throws Exception {
			BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
			try {
				channel.put(input, file.getName(), sftpMonitor, fileCount == 0 ? ChannelSftp.OVERWRITE : ChannelSftp.RESUME);
			} finally {
				try {
					input.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	static public String ssh (String server, String user, String password, String command, boolean requireZeroExitCode)
		throws IOException {
		return ssh(server, 22, user, password, command, requireZeroExitCode);
	}

	static public String ssh (String server, int port, String user, String password, String command, boolean requireZeroExitCode)
		throws IOException {
		if (INFO) info("scar", "SSH: " + command);
		StringBuilder result = new StringBuilder();
		Session session = null;
		ChannelExec channel = null;

		// Connect.
		BufferedReader reader;
		while (true) {
			try {
				session = new JSch().getSession(user, server, port);
				session.setConfig("StrictHostKeyChecking", "no");
				session.setConfig("PubkeyAuthentication", "no");
				session.setConfig("PreferredAuthentications", "password");
				session.setPassword(password);
				session.connect(10000);
				channel = (ChannelExec)session.openChannel("exec");
				channel.setCommand(command);
				channel.setInputStream(null);
				channel.setErrStream(System.err);
				reader = new BufferedReader(new InputStreamReader(channel.getInputStream()));
				channel.connect(10000);
				break;
			} catch (Exception ex) {
				if (TRACE) trace("scar", "Connection error.", ex);
				if (WARN) warn("scar", "Connecting...");
				try {
					Thread.sleep(250);
				} catch (Exception ignored) {
				}
				continue;
			}
		}

		try {
			byte[] buffer = new byte[1024];
			while (true) {
				while (true) {
					String line = reader.readLine();
					if (line == null) break;
					result.append(line);
					result.append('\n');
					if (INFO) info("scar", line);
				}
				if (channel.isClosed()) {
					if (INFO) info("scar", "Exit: " + channel.getExitStatus());
					if (requireZeroExitCode && channel.getExitStatus() != 0)
						throw new RuntimeException("Error executing command: " + command);
					break;
				}
				try {
					Thread.sleep(100);
				} catch (Exception ee) {
				}
			}
		} catch (Exception ex) {
			throw new IOException(ex);
		} finally {
			try {
				if (session != null) session.disconnect();
				if (channel != null) channel.disconnect();
			} catch (Exception ignored) {
			}
		}
		return result.toString();
	}

	static public String http (String url) throws IOException {
		if (INFO) info("scar", "HTTP: " + url);
		Scanner scanner = new Scanner(new URL(url).openStream()).useDelimiter("\\A");
		return scanner.hasNext() ? scanner.next() : "";
	}

	static public ArrayList list (Object... objects) {
		if (objects == null) throw new IllegalArgumentException("objects cannot be null.");
		ArrayList list = new ArrayList(objects.length);
		for (Object o : objects)
			list.add(o);
		return list;
	}

	private Scar () {
	}

	static public interface ProgressMonitor {
		public void progress (float fileProgress, float totalProgress);
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

		// BOZO - Add something that can execute scar methods!
	}
}
