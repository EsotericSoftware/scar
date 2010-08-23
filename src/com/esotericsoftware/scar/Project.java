
package com.esotericsoftware.scar;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.esotericsoftware.wildcard.Paths;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;

/**
 * Generic data structure that contains information needed to perform tasks.
 */
public class Project {
	static private Pattern formatPattern = Pattern.compile("([^\\{]*)\\{([^\\}]+)\\}([^\\{]*)");

	private String dir;
	private HashMap data = new HashMap();
	private String document;

	/**
	 * Creates an empty project, without any default properties.
	 */
	public Project () {
	}

	/**
	 * Creates an empty project, without any default properties, and then loads the specified YAML files.
	 */
	public Project (String path, String... paths) throws IOException {
		if (paths == null) throw new IllegalArgumentException("paths cannot be null.");

		load(path);
		for (String mergePath : paths)
			merge(new Project(mergePath));
	}

	/**
	 * Clears the data in this project and replaces it with the contents of the specified YAML file. The project directory is set
	 * to the directory containing the YAML file.
	 * @param path Path to a YAML project file, or a directory containing a "project.yaml" file.
	 */
	public void load (String path) throws IOException {
		File file = new File(path);
		if (!file.exists()) throw new IllegalArgumentException("Project not found: " + file.getAbsolutePath());
		if (file.isDirectory()) {
			file = new File(file, "project.yaml");
			if (file.exists())
				load(file.getPath());
			else
				dir = Scar.canonical(path) + "/";
			return;
		}
		dir = new File(Scar.canonical(path)).getParent() + "/";

		YamlReader reader = new YamlReader(new FileReader(path));
		try {
			data = reader.read(HashMap.class);
			document = reader.readDocument();
		} catch (YamlException ex) {
			throw new IOException("Error reading YAML file: " + new File(path).getAbsolutePath(), ex);
		} finally {
			reader.close();
		}
	}

	/**
	 * Merges the data in this project with the contents of the specified YAML file. If the specified project has data with the
	 * same key as this project and the value is a List or Map, the values are appended. Otherwise, the value is overwritten.
	 */
	public void merge (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		merge(data, project.data, false);
		if (project.document != null) {
			if (document != null)
				document = project.document + document;
			else
				document = project.document;
		}
		dir = project.dir;
	}

	/**
	 * Replaces the data in this project with the contents of the specified YAML file. If the specified project has data with the
	 * same key as this project, the value is overwritten. Keys in this project that are not in the specified project are not
	 * affected.
	 */
	public void replace (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		merge(data, project.data, true);
		document = project.document;
		dir = project.dir;
	}

	private void merge (Map oldMap, Map newMap, boolean replace) throws IOException {
		for (Object object : newMap.entrySet()) {
			Entry entry = (Entry)object;
			Object key = entry.getKey();
			Object newValue = entry.getValue();
			Object oldValue = oldMap.get(key);
			if (replace || oldValue == null || oldValue.getClass() != newValue.getClass() || newValue instanceof String) {
				oldMap.put(key, newValue);
				continue;
			}
			if (newValue instanceof List) ((List)oldValue).addAll((List)newValue);
			if (newValue instanceof Map) merge((Map)oldValue, (Map)newValue, replace);
		}
	}

	public boolean has (Object key) {
		if (key == null) throw new IllegalArgumentException("key cannot be null.");
		return data.get(key) != null;
	}

	public Object getObject (Object key) {
		return getObject(key, null);
	}

	public Object getObject (Object key, Object defaultValue) {
		if (key == null) throw new IllegalArgumentException("key cannot be null.");
		Object object = data.get(key);
		if (object == null) return defaultValue;
		return object;
	}

	public String get (Object key) {
		return get(key, null);
	}

	public String get (Object key, String defaultValue) {
		String value = (String)getObject(key);
		if (value == null) return defaultValue;
		return value;
	}

	public int getInt (Object key) {
		return getInt(key, 0);
	}

	public int getInt (Object key, int defaultValue) {
		Integer value = (Integer)getObject(key);
		if (value == null) return defaultValue;
		return value;
	}

	public float getFloat (Object key) {
		return getFloat(key, 0);
	}

	public float getFloat (Object key, float defaultValue) {
		Float value = (Float)getObject(key);
		if (value == null) return defaultValue;
		return value;
	}

	public boolean getBoolean (Object key) {
		return getBoolean(key, false);
	}

	public boolean getBoolean (Object key, boolean defaultValue) {
		Boolean value = (Boolean)getObject(key);
		if (value == null) return defaultValue;
		return value;
	}

	/**
	 * Returns a list of strings under the specified key. If the key is a single value, it is placed in a list and returned. If the
	 * key does not exist, an empty list is returned.
	 */
	public List<String> getList (Object key, String... defaultValues) {
		Object object = getObject(key);
		if (!(object instanceof List)) {
			List<String> list = new ArrayList();
			if (object != null) list.add((String)object);
			return list;
		}
		if (object != null) return (List)object;
		if (defaultValues == null) return null;
		List<String> list = new ArrayList();
		for (Object value : defaultValues)
			list.add((String)value);
		return list;
	}

	/**
	 * Returns a list of objects under the specified key. If the key is a single value, it is placed in a list and returned. If the
	 * key does not exist, an empty list is returned.
	 */
	public List getObjectList (Object key, Object... defaultValues) {
		Object object = getObject(key);
		if (!(object instanceof List)) {
			List list = new ArrayList();
			if (object != null) list.add(object);
			return list;
		}
		if (object != null) return (List)object;
		if (defaultValues == null) return null;
		List list = new ArrayList();
		for (Object value : defaultValues)
			list.add(value);
		return list;
	}

	public Map<String, String> getMap (Object key, String... defaultValues) {
		Map<String, String> map = (Map)getObject(key);
		if (map == null) {
			if (defaultValues == null) return null;
			map = new HashMap();
			for (int i = 0, n = defaultValues.length; i < n;) {
				Object defaultKey = defaultValues[i++];
				Object defaultValue = i < n ? defaultValues[i++] : null;
				map.put((String)key, (String)defaultValue);
			}
		}
		return map;
	}

	public Map getObjectMap (Object key, Object... defaultValues) {
		Map map = (Map)getObject(key);
		if (map == null) {
			if (defaultValues == null) return null;
			map = new HashMap();
			for (int i = 0, n = defaultValues.length; i < n;) {
				Object defaultKey = defaultValues[i++];
				Object defaultValue = i < n ? defaultValues[i++] : null;
				map.put(defaultKey, defaultValue);
			}
		}
		return map;
	}

	/**
	 * Uses the strings under the specified key to {@link Paths#glob(String, String...) glob} paths.
	 */
	public Paths getPaths (String key) {
		Paths paths = new Paths();
		Object object = data.get(key);
		if (object instanceof List) {
			for (Object dirPattern : (List)object)
				paths.glob(path((String)dirPattern));
		} else if (object instanceof String) {
			paths.glob(path((String)object));
		}
		return paths;
	}

	/**
	 * Returns the specified path if it is an absolute path, otherwise returns the path relative to this project's directory.
	 */
	public String path (String path) {
		if (dir == null) return path;
		int pipeIndex = path.indexOf('|');
		if (pipeIndex > -1) {
			// Handle wildcard search patterns.
			return path(path.substring(0, pipeIndex)) + path.substring(pipeIndex);
		}
		if (new File(path).isAbsolute()) return path;
		return dir + path;
	}

	public void set (Object key, Object object) {
		if (key == null) throw new IllegalArgumentException("key cannot be null.");
		data.put(key, object);
	}

	public void setDirectory (String dir) {
		this.dir = Scar.canonical(dir);
	}

	public String getDirectory () {
		return dir;
	}

	public String getDocument () {
		return document;
	}

	public void setDocument (String document) {
		this.document = document;
	}

	public void remove (Object key) {
		data.remove(key);
	}

	/**
	 * Removes an item from a list or map. If the data under the specified key is a list, the entry equal to the specified value is
	 * removed. If the data under the specified key is a map, the entry with the key specified by value is removed.
	 */
	public void remove (Object key, Object value) {
		Object object = data.get(key);
		if (object instanceof Map)
			((Map)object).remove(object);
		else if (object instanceof List)
			((List)object).remove(object);
		else
			data.remove(key);
	}

	/**
	 * Replaces property names surrounded by curly braces with the value from this project.
	 */
	public String format (String text) {
		Matcher matcher = formatPattern.matcher(text);
		StringBuilder buffer = new StringBuilder(128);
		while (matcher.find()) {
			buffer.append(matcher.group(1));
			String name = matcher.group(2);
			Object value = data.get(name);
			if (value != null)
				buffer.append(value);
			else
				buffer.append(name);
			buffer.append(matcher.group(3));
		}
		if (buffer.length() == 0) return text;
		return buffer.toString();
	}

	public void clear () {
		data.clear();
	}

	public String toString () {
		if (has("name")) return get("name");
		return data.toString();
	}
}
