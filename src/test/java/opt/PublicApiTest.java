package opt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the promise that OR-Tools stays an implementation detail: no public or protected
 * member of a class outside {@code *.internal} and {@code opt.examples} mentions a
 * {@code com.google.ortools} (or protobuf) type in its signature.
 */
class PublicApiTest {

    @Test
    void publicApiDoesNotExposeOrTools() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Class<?> c : publicApiClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                if (exported(m.getModifiers()) && !m.isSynthetic()) check(c, m, leaks);
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (exported(k.getModifiers())) check(c, k, leaks);
            }
            for (Field f : c.getDeclaredFields()) {
                if (exported(f.getModifiers()) && leaks(f.getGenericType())) leaks.add(c.getName() + "." + f.getName());
            }
        }
        assertThat(leaks).as("public signatures mentioning OR-Tools types").isEmpty();
    }

    private static void check(Class<?> owner, Executable e, List<String> leaks) {
        boolean leak = Stream.of(e.getGenericParameterTypes()).anyMatch(PublicApiTest::leaks)
                || (e instanceof Method m && leaks(m.getGenericReturnType()));
        if (leak) leaks.add(owner.getName() + "#" + e.getName());
    }

    private static boolean leaks(Type t) {
        String name = t.getTypeName();
        return name.contains("com.google.ortools") || name.contains("com.google.protobuf");
    }

    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static List<Class<?>> publicApiClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(Optimize.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String name = root.relativize(p).toString().replace('/', '.').replace('\\', '.');
                name = name.substring(0, name.length() - ".class".length());
                if (name.contains(".internal.") || name.startsWith("opt.examples.")) continue;
                Class<?> c = Class.forName(name, false, PublicApiTest.class.getClassLoader());
                if (Modifier.isPublic(c.getModifiers())) out.add(c);
            }
        }
        assertThat(out).as("scanned classes").isNotEmpty();
        return out;
    }
}
