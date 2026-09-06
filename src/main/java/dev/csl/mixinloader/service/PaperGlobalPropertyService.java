package dev.csl.mixinloader.service;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class PaperGlobalPropertyService implements IGlobalPropertyService {
	private static final Map<String, Object> PROPERTIES = new ConcurrentHashMap<>();

	@Override
	public IPropertyKey resolveKey(String name) {
		return new Key(name);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(IPropertyKey key) {
		return (T) PROPERTIES.get(key.toString());
	}

	@Override
	public void setProperty(IPropertyKey key, Object value) {
		PROPERTIES.put(key.toString(), value);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(IPropertyKey key, T defaultValue) {
		Object value = PROPERTIES.get(key.toString());
		return value != null ? (T) value : defaultValue;
	}

	@Override
	public String getPropertyString(IPropertyKey key, String defaultValue) {
		Object value = PROPERTIES.get(key.toString());
		return value != null ? String.valueOf(value) : defaultValue;
	}

	private record Key(String name) implements IPropertyKey {
			private Key(String name) {
				this.name = Objects.requireNonNull(name, "name");
			}

			@Override
			public @NotNull String toString() {
				return this.name;
			}

			@Override
			public boolean equals(Object obj) {
				return this == obj || (obj instanceof Key other && this.name.equals(other.name()));
			}

	}
}