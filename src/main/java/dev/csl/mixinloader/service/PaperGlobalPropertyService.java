package dev.csl.mixinloader.service;

import com.google.auto.service.AutoService;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

@SuppressWarnings("unused")
@AutoService(IGlobalPropertyService.class)
public class PaperGlobalPropertyService implements IGlobalPropertyService {
	private record Key(String name) implements IPropertyKey {
		private Key(final String name) { this.name = Objects.requireNonNull(name, "name"); }
		@Override public @NotNull String toString() { return this.name; }

		@Override
		public boolean equals(final Object obj) {
			return (
				this == obj ||
				(obj instanceof final Key other &&
					this.name.equals(other.name()))
			);
		}
	}

	private static final Map<String, Object> PROPERTIES = new ConcurrentHashMap<>();

	@Override
	public IPropertyKey resolveKey(final String name) { return new Key(name); }

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(final IPropertyKey key) { return (T) PROPERTIES.get(key.toString()); }

	@Override
	public void setProperty(final IPropertyKey key, final Object value) { PROPERTIES.put(key.toString(), value); }

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(final IPropertyKey key, final T defaultValue) {
		final Object value = PROPERTIES.get(key.toString());
		return value != null ? (T) value : defaultValue;
	}

	@Override
	public String getPropertyString(final IPropertyKey key, final String defaultValue) {
		final Object value = PROPERTIES.get(key.toString());
		return value != null ? String.valueOf(value) : defaultValue;
	}
}
