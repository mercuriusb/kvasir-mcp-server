package org.kvasir.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.kvasir.entity.ChunkType;
import org.kvasir.entity.DocChunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Parses a {@code .java} file from an unpacked sources JAR.
 * <p>
 * One file yields up to three kinds of chunk:
 * <ul>
 * <li>{@link ChunkType#JAVADOC} — the class description, plus one chunk per public method with its
 * signature and Javadoc. Every one of them carries the fully qualified class name.</li>
 * <li>{@link ChunkType#SOURCE} — the complete source of the file, which is what
 * {@code get_class_source} hands back.</li>
 * <li>{@link ChunkType#PACKAGE_DOC} — for {@code package-info.java}, whose Javadoc belongs to no
 * class at all. Such a file has no type declaration, so it must not be skipped just because the
 * usual "iterate the classes" loop finds nothing.</li>
 * </ul>
 */
@ApplicationScoped
public class JavaSourceParser extends TextDocumentParser {

    private static final String PACKAGE_INFO = "package-info.java";

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".java");
    }

    @Override
    public List<DocChunk> parse(SourceLocation location, String content) {
        Optional<CompilationUnit> parsed = new JavaParser().parse(content).getResult();
        if (parsed.isEmpty()) {
            // a source JAR with one unparsable file must not fail the whole scan
            return List.of();
        }
        CompilationUnit unit = parsed.get();
        String packageName = unit.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("");

        if (location.path().endsWith(PACKAGE_INFO)) {
            return packageDoc(location, unit, packageName);
        }

        List<DocChunk> chunks = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            if (!type.isPublic()) {
                continue;
            }
            String className = qualify(packageName, type.getNameAsString());
            addTypeChunk(chunks, location, type, className);
            addMethodChunks(chunks, location, type, className);
            addSourceChunk(chunks, location, content, className);
        }
        return chunks;
    }

    /**
     * The Javadoc of {@code package-info.java} hangs off the package declaration, not off any type.
     * The chunk therefore carries the package name where other chunks carry a file or class path.
     */
    private static List<DocChunk> packageDoc(SourceLocation location, CompilationUnit unit,
            String packageName) {
        // The Javadoc may sit on the package declaration or, depending on where the blank lines
        // are, end up as a free-standing comment of the compilation unit. Both count.
        Optional<Javadoc> javadoc = unit.getPackageDeclaration()
                .flatMap(PackageDeclaration::getComment)
                .filter(Comment::isJavadocComment)
                .map(comment -> comment.asJavadocComment().parse());
        if (javadoc.isEmpty()) {
            javadoc = unit.getAllComments().stream()
                    .filter(Comment::isJavadocComment)
                    .map(comment -> comment.asJavadocComment().parse())
                    .findFirst();
        }

        String description = javadoc.map(doc -> doc.getDescription().toText().strip()).orElse("");
        if (description.isEmpty()) {
            return List.of();
        }

        DocChunk chunk = new DocChunk(location.chunkId("package"), location.project(),
                location.version(), ChunkType.PACKAGE_DOC, packageName, packageName,
                packageName + "\n\n" + description);
        javadoc.flatMap(JavaSourceParser::since).ifPresent(chunk::setSince);
        return List.of(chunk);
    }

    private static void addTypeChunk(List<DocChunk> chunks, SourceLocation location,
            TypeDeclaration<?> type, String className) {
        String description = type.getJavadoc()
                .map(doc -> doc.getDescription().toText().strip())
                .orElse("");
        if (description.isEmpty()) {
            return;
        }
        DocChunk chunk = chunk(location, ChunkType.JAVADOC, className, className,
                className + "\n\n" + description, className);
        type.getJavadoc().flatMap(JavaSourceParser::since).ifPresent(chunk::setSince);
        chunks.add(chunk);
    }

    private static void addMethodChunks(List<DocChunk> chunks, SourceLocation location,
            TypeDeclaration<?> type, String className) {
        boolean insideInterface = type instanceof ClassOrInterfaceDeclaration declaration
                && declaration.isInterface();

        for (MethodDeclaration method : type.getMethods()) {
            if (!isPublic(method, insideInterface)) {
                continue;
            }
            String signature = signatureOf(method);
            String description = method.getJavadoc()
                    .map(doc -> doc.getDescription().toText().strip())
                    .orElse("");
            String text = description.isEmpty() ? signature : signature + "\n\n" + description;

            DocChunk chunk = chunk(location, ChunkType.JAVADOC, className + "#" + signature,
                    className, text, className);
            chunk.setHeading(signature);
            method.getJavadoc().flatMap(JavaSourceParser::since).ifPresent(chunk::setSince);
            chunks.add(chunk);
        }
    }

    /**
     * Renders the signature the way it appears in the source. JavaParser's own
     * {@code getDeclarationAsString} drops the type parameters, which would turn
     * {@code <T> T readValue(...)} into {@code T readValue(...)} — and the signature is exactly
     * what an agent gets to see as the heading of the chunk.
     */
    private static String signatureOf(MethodDeclaration method) {
        String declaration = method.getDeclarationAsString(false, false, true).strip();
        if (method.getTypeParameters().isEmpty()) {
            return declaration;
        }
        String typeParameters = method.getTypeParameters().stream()
                .map(TypeParameter::asString)
                .collect(Collectors.joining(", "));
        return "<" + typeParameters + "> " + declaration;
    }

    /** Methods declared in an interface are public even without the modifier. */
    private static boolean isPublic(MethodDeclaration method, boolean insideInterface) {
        return method.isPublic()
                || (insideInterface && !method.isPrivate() && !method.isStatic());
    }

    private static void addSourceChunk(List<DocChunk> chunks, SourceLocation location,
            String content, String className) {
        chunks.add(chunk(location, ChunkType.SOURCE, "source", className, content, className));
    }

    private static DocChunk chunk(SourceLocation location, ChunkType type, String discriminator,
            String heading, String text, String className) {
        DocChunk chunk = new DocChunk(location.chunkId(discriminator), location.project(),
                location.version(), type, location.path(), heading, text);
        chunk.setFullyQualifiedClassName(className);
        return chunk;
    }

    private static Optional<String> since(Javadoc javadoc) {
        return javadoc.getBlockTags().stream()
                .filter(tag -> tag.getType() == JavadocBlockTag.Type.SINCE)
                .map(tag -> tag.getContent().toText().strip())
                .filter(content -> !content.isEmpty())
                .findFirst();
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }
}
