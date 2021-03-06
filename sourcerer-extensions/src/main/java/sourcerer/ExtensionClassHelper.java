/*
 * Copyright 2016 Layne Mobile, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sourcerer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import sourcerer.io.Reader;
import sourcerer.io.Writeable;
import sourcerer.io.Writer;

import static sourcerer.ExtensionClass.Kind.StaticDelegate;
import static sourcerer.ExtensionMethod.Kind.ReturnThis;
import static sourcerer.ExtensionMethod.Kind.Void;

final class ExtensionClassHelper implements Writeable {
    private final ExtensionClass.Kind kind;
    private final TypeElement element;
    private final ExtensionMethodHelper instanceMethod;
    private final ImmutableList<ExtensionMethodHelper> methods;
    private final MethodInk methodInk;

    private ExtensionClassHelper(ExtensionClass.Kind kind, TypeElement element, ExtensionMethodHelper instanceMethod,
            List<ExtensionMethodHelper> methods) {
        if (instanceMethod == null) {
            throw new IllegalArgumentException(element.getQualifiedName() + " must have an instance method specified");
        } else if (!ClassName.get(element).equals(TypeName.get(instanceMethod.method.getReturnType()))) {
            throw new IllegalArgumentException(
                    element.getQualifiedName() + " instance method must return its own type");
        } else if (methods.size() == 0) {
            throw new IllegalArgumentException(element.getQualifiedName() + " has no annotated methods to process");
        }
        this.kind = kind;
        this.element = element;
        this.instanceMethod = instanceMethod;
        this.methods = ImmutableList.copyOf(methods);
        this.methodInk = new MethodInk();
    }

    static ExtensionClassHelper process(ExtensionClass.Kind kind, TypeElement element) {
        ExtensionMethodHelper instanceMethod = null;
        List<ExtensionMethodHelper> methods = new ArrayList<>();
        for (Element memberElement : element.getEnclosedElements()) {
            ExtensionMethodHelper method = ExtensionMethodHelper.process(memberElement);
            if (method == null) continue;
            switch (method.kind) {
                case Instance:
                    if (instanceMethod != null) {
                        String format = "Cannot have instance method '%s' when '%s' is already defined";
                        String message = String.format(format, method.name(), instanceMethod.name());
                        throw new IllegalStateException(message);
                    }
                    instanceMethod = method;
                    break;
                default:
                    methods.add(method);
                    break;
            }
        }
        return new ExtensionClassHelper(kind, element, instanceMethod, methods);
    }

    public static Reader.Parser<List<MethodSpec>> parser(TypeName typeName) {
        final MethodParser methodParser = new MethodParser(typeName);
        return new Reader.Parser<List<MethodSpec>>() {
            @Override public List<MethodSpec> parse(Reader reader) throws IOException {
                return reader.readList(methodParser);
            }
        };
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ExtensionClassHelper that = (ExtensionClassHelper) o;

        return element.getQualifiedName().toString().equals(that.element.getQualifiedName().toString());
    }

    @Override public int hashCode() {
        return element.getQualifiedName().toString().hashCode();
    }

    @Override public void writeTo(Writer writer) throws IOException {
        writer.writeList(methods, methodInk);
    }

    @Override public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("element", element)
                .add("kind", kind)
                .toString();
    }

    private final class MethodInk implements Writer.Inker<ExtensionMethodHelper> {
        private MethodInk() {}

        @Override public boolean pen(Writer writer, ExtensionMethodHelper extensionMethodHelper) throws IOException {
            ExecutableElement methodElement = extensionMethodHelper.method;
            ExtensionMethod.Kind methodKind = extensionMethodHelper.kind;

            // Write method name
            String methodName = methodElement.getSimpleName().toString();
            writer.writeString(methodName);

            // Write modifiers
            Set<Modifier> modifiers = methodElement.getModifiers();
            if (kind == StaticDelegate) {
                // add Static Modifier
                modifiers = new HashSet<>(modifiers);
                modifiers.add(Modifier.STATIC);
                if (methodKind == ReturnThis) {
                    methodKind = Void;
                }
            }
            writer.writeModifiers(modifiers);

            // Write type parameters
            writer.writeTypeParams(methodElement.getTypeParameters());

            // Write parameters
            String params = writer.writeParams(methodElement.getParameters());

            // Write return annotations
            writer.writeAnnotations(new ArrayList<>(extensionMethodHelper.returnAnnotations));

            // Write classType
            writer.writeClassName(ClassName.get(element));

            // Write statement
            String statement = String.format("$T.%s().%s(%s)", instanceMethod.name(), extensionMethodHelper.name(),
                    params);
            writer.writeString(statement);

            // Write method kind
            writer.writeString(methodKind.name());
            switch (methodKind) {
                case Return:
                    TypeName returnType = TypeName.get(methodElement.getReturnType());
                    writer.writeTypeName(returnType);
                    break;
                case ReturnThis:
                case Void:
                    break;
                default:
                    throw new IllegalStateException("invalid method kind");
            }
            return true;
        }
    }

    private static final class MethodParser implements Reader.Parser<MethodSpec> {
        private final TypeName type;

        private MethodParser(TypeName type) {
            this.type = type;
        }

        @Override public MethodSpec parse(Reader reader) throws IOException {
            // Read method name
            String methodName = reader.readString();

            // Read modifiers
            Set<Modifier> modifiers = reader.readModifiers();

            // Create method builder with modifiers
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                    .addModifiers(modifiers.toArray(new Modifier[modifiers.size()]))
                    // Read type parameters
                    .addTypeVariables(reader.readTypeParams())
                    // Read Parameters
                    .addParameters(reader.readParams())
                    // Read return annotations
                    .addAnnotations(reader.readAnnotations());

            // Read classType
            ClassName classType = reader.readClassName();

            // Read statement
            String statement = reader.readString();

            // Read method kind
            ExtensionMethod.Kind methodKind = ExtensionMethod.Kind.fromName(reader.readString());
            switch (methodKind) {
                case Return:
                    TypeName returnType = reader.readTypeName();
                    methodBuilder.returns(returnType);
                    statement = "return " + statement;
                    methodBuilder.addStatement(statement, classType);
                    break;
                case ReturnThis:
                    methodBuilder.returns(type);
                    methodBuilder.addStatement(statement, classType);
                    methodBuilder.addStatement("return this");
                    break;
                case Void:
                    methodBuilder.addStatement(statement, classType);
                    break;
                default:
                    throw new IllegalStateException("invalid method kind");
            }

            // build
            return methodBuilder.build();
        }
    }
}
