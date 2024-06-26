package com.quicklink.javalinfly.processor.round;

import com.quicklink.javalinfly.annotation.JavalinFlyInjector;
import com.quicklink.javalinfly.ResponseType;
import com.quicklink.javalinfly.Vars;
import com.quicklink.javalinfly.openapi.OpenApiTranslator;
import com.quicklink.javalinfly.openapi.SwaggerUIHtmlGenerator;
import com.quicklink.javalinfly.openapi.model.OpenApi;
import com.quicklink.javalinfly.processor.JavalinFlyConfig;
import com.quicklink.javalinfly.processor.Round;
import com.quicklink.javalinfly.processor.utils.JsonUtils;
import com.quicklink.javalinfly.processor.utils.Messager;
import com.quicklink.javalinfly.processor.utils.ProcessorUtil;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

public class GeneratorRound extends Round {

  public record Input(Filer filer, JavalinFlyInjectorRound injectorRound,
                      ControllersRound controllersRound) {

  }

  public static String SIMPLE_CLASS_NAME = "GeneratedClass";
  public static String PACKAGE_NAME = "com.quicklink.javalinfly.gen";
  public static String FULL_CLASS = PACKAGE_NAME + "." + SIMPLE_CLASS_NAME;
  public static String METHOD_NAME = "init";

  private final Input input;

  public GeneratorRound(Input input) {
    this.input = input;
  }

  @Override
  protected void run() {
//        String packageName = elementUtils.getPackageOf(annotatedElement).getQualifiedName().toString();

    for (var entry : input.controllersRound().selectedRoles.entrySet()) {
      if (!input.injectorRound().injectorRoles.contains(entry.getKey())) {
        Messager.error(entry.getValue(), "Role '%s' is missing at @%s", entry.getKey(),
            JavalinFlyInjector.class.getSimpleName());
        return;
      }
    }
    generateClass(input.injectorRound().injectorElement);
//            error(controllers.iterator().next(), "Error generating class %s: Testing stuff", FULL_CLASS);
//            error(e, "errore: classe %s non ha l'annotazione niagara 4", e.getSimpleName().toString());

//            return true;
  }

  private String addClassImport(Class<?> cl) {
    return "import " + cl.getName() + ";\n";
  }

  private void generateClass(Element element) {
    String openApiStatements = "\n";
    if (input.injectorRound().javalinFlyInjectorAnn.generateDocumentation()) {
      String allRoles = String.join(",", input.injectorRound().injectorRoles.stream()
          .map(roleName -> ProcessorUtil.getClassNameWithoutAnnotations(
              input.injectorRound().rolesTypeMirror) + "." + roleName)
          .collect(Collectors.toSet()));

      openApiStatements =
          "        {\n" +
              "            OpenApiTranslator openApiTranslator = new OpenApiTranslator();\n" +
              String.join("", input.controllersRound().openApiStatements) +
              "            OpenApi openApi = openApiTranslator.build();\n" +
              "            config.openapi.edit(openApi);\n" +
              "            String openApiSpec = %s.get().serialize(openApi);\n".formatted(
                  JsonUtils.class.getName()) +
//              "            Vars.openApiSpec(openApiSpec);\n" +
              "            Vars.swaggerUi(SwaggerUIHtmlGenerator.generateSwaggerUIHtml(openApiSpec));\n"
              +
              "        }\n" +
              "        {\n" +
              "            javalin.addHandler(HandlerType.GET, \"/openapi\", ctx -> {\n" +
              "                 ctx.html(Vars.swaggerUi());\n" +
              "            }, new RouteRole[]{%s});\n".formatted(allRoles) +
              "        }\n";

      try {
        FileObject file = input.filer().createResource(StandardLocation.CLASS_OUTPUT, "",
            Vars.RESOURCE_FILE_SPEC);
        try (Writer writer = file.openWriter()) {
          writer.write(
              input.controllersRound().schemasEncoded == null ? "{}" : input.controllersRound().schemasEncoded);
        }
      } catch (IOException e) {
        Messager.error(element, "Error generating resource %s: %s", Vars.RESOURCE_FILE_SPEC,
            e.getMessage());

      }

    }

    String source = "package " + PACKAGE_NAME + ";\n\n" +
        addClassImport(Consumer.class) +
        addClassImport(HashSet.class) +
        addClassImport(Arrays.class) +
        addClassImport(Collections.class) +

        addClassImport(JavalinFlyConfig.class) +
        addClassImport(OpenApiTranslator.class) +
        addClassImport(SwaggerUIHtmlGenerator.class) +
        addClassImport(OpenApi.class) +
        addClassImport(Vars.class) +
        addClassImport(ResponseType.class) +

        "import io.javalin.Javalin;\n" +
        "import io.javalin.http.HandlerType;\n" +
        "import io.javalin.http.Context;\n" +
        "import io.javalin.http.Handler;\n" +
        "import io.javalin.security.RouteRole;\n" +

        "public class " + SIMPLE_CLASS_NAME + " {\n" +
        "    public " + SIMPLE_CLASS_NAME + "(){}\n" +
        String.join("", input.controllersRound().handlersField) +
        "    public void " + METHOD_NAME
        + "(Javalin javalin, Consumer<JavalinFlyConfig> configFun) {\n" +
        "        JavalinFlyConfig config = new JavalinFlyConfig();\n" +
        "        configFun.accept(config);\n" +
        openApiStatements +
        String.join("", input.controllersRound().endpoints) +
        "    }\n" +
        "}\n";

    Javalin app;

    try {
      JavaFileObject sourceFile = input.filer().createSourceFile(FULL_CLASS, element);
      try (Writer writer = sourceFile.openWriter()) {
        writer.write(source);
      }

    } catch (IOException e) {
      Messager.error(element, "Error generating class %s: %s", FULL_CLASS, e.getMessage());
    }

  }


}
