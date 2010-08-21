
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

// BOZO - Add xpath style data lookup.

public class Project {
	static private Pattern formatPattern = Pattern.compile("([^\\{]*)\\{([^\\}]+)\\}([^\\{]*)");

	private HashMap data = new HashMap();
	private String document;

	public Project () {
	}

	public Project (String path, String... paths) throws IOException {
		if (paths == null) throw new IllegalArgumentException("paths cannot be null.");

		load(path);
		for (String mergePath : paths)
			merge(new Project(mergePath));
	}

	public void load (String path) throws IOException {
		File file = new File(path);
		if (!file.exists()) throw new IllegalArgumentException("Project not found: " + file.getAbsolutePath());
		if (file.isDirectory()) {
			file = new File(file, "project.yaml");
			if (file.exists()) load(file.getPath());
			return;
		}

		YamlReader reader = new YamlReader(new FileReader(path));
		try {
			data = reader.read(HashMap.class);
			document = reader.read(String.class);
		} catch (YamlException ex) {
			throw new IOException("Error reading YAML file: " + new File(path).getAbsolutePath(), ex);
		} finally {
			reader.close();
		}
	}

	public void merge (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		merge(data, project.data, false);
		if (project.document != null) document = project.document;
	}

	public void replace (Project project) throws IOException {
		if (project == null) throw new IllegalArgumentException("project cannot be null.");
		merge(data, project.data, true);
		document = project.document;
	}

	private void merge (HashMap oldMap, HashMap newMap, boolean replace) throws IOException {
		for (Object object : newMap.entrySet()) {
			Entry entry = (Entry)object;
			Object key = entry.getKey();
			Object newValue = entry.getValue();
			Object oldValue = oldMap.get(key);
			if (replace || oldValue == null || oldValue.getClass() != newValue.getClass() || newValue instanceof String) {
				oldMap.put(key, newValue);
				continue;
			}
			if (newValue instanceof ArrayList) ((ArrayList)oldValue).addAll((ArrayList)newValue);
			if (newValue instanceof HashMap) merge((HashMap)oldValue, (HashMap)newValue, replace);
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

	public Paths getPaths (String name) {
		Paths paths = new Paths();
		Object object = data.get(name);
		if (object instanceof List) {
			for (Object dirPattern : (List)object)
				paths.glob((String)dirPattern);
		} else if (object instanceof String) {
			paths.glob((String)object);
		}
		return paths;
	}

	public void set (Object name, Object object) {
		if (name == null) throw new IllegalArgumentException("key cannot be null.");
		data.put(name, object);
	}

	public String getDocument () {
		return document;
	}

	public void setDocument (String document) {
		this.document = document;
	}

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
