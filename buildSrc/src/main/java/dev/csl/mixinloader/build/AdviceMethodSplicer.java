package dev.csl.mixinloader.build;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Replaces selected R8-emitted methods with the corresponding complete methods
 * from the untouched javac class. Class structure and all other methods remain
 * from R8.
 */
public final class AdviceMethodSplicer {
	private AdviceMethodSplicer() {
	}

	/**
	 * Method keys use the class-file form {@code name+descriptor}, for example
	 * {@code onExit(Ljava/lang/Class;Ljava/lang/Class;)V}.
	 */
	public static byte[] replaceMethods(
		byte[] r8Class,
		byte[] javacClass,
		String... methodKeys
	) {
		ClassNode r8 = read(r8Class);
		ClassNode javac = read(javacClass);

		if (!r8.name.equals(javac.name)) {
			throw new IllegalArgumentException(
				"Cannot splice different classes: " + r8.name + " and " + javac.name
			);
		}

		Set<String> requested = new LinkedHashSet<>(Arrays.asList(methodKeys));
		Map<String, MethodNode> originals = new HashMap<>();
		for (MethodNode method : javac.methods) {
			String key = key(method);
			if (requested.contains(key)) {
				originals.put(key, method);
			}
		}

		Set<String> missing = new LinkedHashSet<>(requested);
		missing.removeAll(originals.keySet());
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException(
				"Pristine class " + javac.name + " is missing methods " + missing
			);
		}

		for (String requestedKey : requested) {
			MethodNode original = originals.get(requestedKey);

			// These advice method names are unique in their declaring classes.
			// Removing by name also removes an optimizer-strengthened descriptor
			// rather than leaving a second annotated advice method behind.
			r8.methods.removeIf(method -> method.name.equals(original.name));
			r8.methods.add(original);
		}

		ClassWriter writer = new ClassWriter(0);
		r8.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static String key(MethodNode method) {
		return method.name + method.desc;
	}
}
