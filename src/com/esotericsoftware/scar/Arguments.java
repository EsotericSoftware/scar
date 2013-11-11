
package com.esotericsoftware.scar;

import java.util.LinkedHashMap;

/**
 * Stores command line arguments as name/value pairs. Arguments containing an equals sign are considered a name/value pair. All
 * other arguments are stored as a name/value pair with a null value.
 */
public class Arguments {
	private final LinkedHashMap<String, String> parameters = new LinkedHashMap();

	public Arguments () {
	}

	public Arguments (String[] args) {
		this(args, 0);
	}

	public Arguments (String[] args, int startIndex) {
		for (int i = startIndex; i < args.length; i++) {
			String[] nameValuePair = args[i].split("=", 2);
			if (nameValuePair.length == 2)
				parameters.put(nameValuePair[0], nameValuePair[1]);
			else
				parameters.put(args[i], null);
		}
	}

	/**
	 * Returns true if the argument was specified.
	 */
	public boolean has (String name) {
		if (name == null) throw new IllegalArgumentException("name cannot be null.");
		return parameters.containsKey(name);
	}

	/**
	 * Returns the value of the argument with the specified name, or null if the argument was specified without a value or was not
	 * specified.
	 */
	public String get (String name) {
		if (name == null) throw new IllegalArgumentException("name cannot be null.");
		return parameters.get(name);
	}

	/**
	 * Returns the value of the argument with the specified name, or the specified default value if the argument was specified
	 * without a value or was not specified.
	 */
	public String get (String name, String defaultValue) {
		String value = parameters.get(name);
		if (value == null) return defaultValue;
		return value;
	}

	public void set (String name) {
		set(name, null);
	}

	public void set (String name, String value) {
		if (name == null) throw new IllegalArgumentException("name cannot be null.");
		parameters.put(name, value);
	}

	public int count () {
		return parameters.size();
	}

	public String remove (String name) {
		if (name == null) throw new IllegalArgumentException("name cannot be null.");
		return parameters.remove(name);
	}

	public String toString () {
		StringBuffer buffer = new StringBuffer(100);
		for (String param : parameters.keySet()) {
			if (buffer.length() > 1) buffer.append(' ');
			String value = get(param);
			if (value == null)
				buffer.append(param);
			else {
				buffer.append(param);
				buffer.append('=');
				buffer.append(value);
			}
		}
		return buffer.toString();
	}
}
