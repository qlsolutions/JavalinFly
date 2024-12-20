package com.quicklink.javalinfly.processor.round;

import com.quicklink.javalinfly.annotation.Body;
import com.quicklink.javalinfly.annotation.Controller;
import com.quicklink.javalinfly.annotation.Delete;
import com.quicklink.javalinfly.FileResponse;
import com.quicklink.javalinfly.annotation.Get;
import com.quicklink.javalinfly.annotation.JavalinFlyInjector;
import com.quicklink.javalinfly.annotation.Path;
import com.quicklink.javalinfly.annotation.Post;
import com.quicklink.javalinfly.annotation.Put;
import com.quicklink.javalinfly.annotation.Query;
import com.quicklink.javalinfly.Response;
import com.quicklink.javalinfly.ResponseType;
import com.quicklink.javalinfly.SuccessResponse;
import com.quicklink.javalinfly.exception.handler.impl.QueryParamNotFoundException;
import com.quicklink.javalinfly.openapi.OpenApiUtil;
import com.quicklink.javalinfly.openapi.model.Schema;
import com.quicklink.javalinfly.processor.Round;
import com.quicklink.javalinfly.processor.utils.JsonUtils;
import com.quicklink.javalinfly.processor.utils.Messager;
import com.quicklink.javalinfly.processor.utils.ProcessorUtil;
import com.quicklink.javalinfly.processor.utils.StringUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ControllersRound extends Round {

  public record Input(Types typeUtils, Elements elementUtils, Set<? extends Element> controllers) {

  }

  private final Input input;
  public JavalinFlyInjectorRound injectorRound;

  public Map<String, ExecutableElement> selectedRoles = new HashMap<>();

  // generator
  public final Set<String> endpoints = new HashSet<>();
  public final Set<String> handlersField = new HashSet<>();
  public final List<String> openApiStatements = new ArrayList<>();


  public String schemasEncoded;

  public ControllersRound(Input input) {
    this.input = input;
  }


  @Override
  protected void run() {
    JavalinFlyInjector injector =  injectorRound.javalinFlyInjectorAnn;


    // ** open api
    Map<String, Schema> schemaMap = new HashMap<>();

    schemaMap.put("CustomType", Schema.builder().type("string").build());
    schemaMap.put("SuccessResponse", Schema.builder().type("object").build());

    OpenApiUtil openApiUtil = new OpenApiUtil(input.typeUtils(), input.elementUtils());

    // Iterate over all @Controller annotated elements
    for (Element elementController : input.controllers()) {

      // Check if a class has been annotated with @Controller
      if (elementController.getKind() != ElementKind.CLASS) {
        Messager.error(elementController, "Only classes can be annotated with @%s",
            Controller.class.getSimpleName());
        return;
      }

      TypeElement annotatedElement = (TypeElement) elementController;

      Controller controller = annotatedElement.getAnnotation(Controller.class);

      String varDecl = registerConstructors(annotatedElement);
      if (varDecl == null) {
        Messager.error(annotatedElement, "Class %s missing an empty constructor",
            annotatedElement.getQualifiedName());
        return;
      }

      for (Element enclosedElement : annotatedElement.getEnclosedElements()) {
        if (enclosedElement.getKind() != ElementKind.METHOD) {
          continue;
        }
        if (!enclosedElement.getModifiers().contains(Modifier.PUBLIC)) {
          continue;
        }

        ExecutableElement executableElement = (ExecutableElement) enclosedElement;

        Get get = executableElement.getAnnotation(Get.class);
        Post post = executableElement.getAnnotation(Post.class);
        Put put = executableElement.getAnnotation(Put.class);
        Delete delete = executableElement.getAnnotation(Delete.class);

        String handlerType = null;
        String responseType = null;
        String[] handlerRoles;
        String summary;
        ResponseType handlerResponseType = null;
        boolean deprecated;
        if (get != null) {
          handlerType = "GET";
          handlerResponseType = get.responseType();
          responseType = get.responseType().compiled();
          handlerRoles = get.roles();
          summary = get.summary();
          deprecated = get.deprecated();
        } else if (post != null) {
          handlerType = "POST";
          handlerResponseType = post.responseType();
          responseType = post.responseType().compiled();
          handlerRoles = post.roles();
          summary = post.summary();
          deprecated = post.deprecated();
        } else if (put != null) {
          handlerType = "PUT";
          handlerResponseType = put.responseType();
          responseType = put.responseType().compiled();
          handlerRoles = put.roles();
          summary = put.summary();
          deprecated = put.deprecated();
        } else if (delete != null) {
          handlerType = "DELETE";
          handlerResponseType = delete.responseType();
          responseType = delete.responseType().compiled();
          handlerRoles = delete.roles();
          summary = delete.summary();
          deprecated = delete.deprecated();
        } else {
          continue;
        }

        if (handlerRoles.length == 0 && injector.defaultRoles().length > 0) {
          handlerRoles = injector.defaultRoles();
        }

        String rolesStr = "";
        if (handlerRoles.length > 0) {
          for (String role : handlerRoles) {
            selectedRoles.put(role, executableElement);
          }

          rolesStr = ", new RouteRole[]{";
          rolesStr += String.join(",", Arrays.stream(handlerRoles)
              .map(roleName -> ProcessorUtil.getClassNameWithoutAnnotations(injectorRound.rolesTypeMirror) + "."
                  + roleName)
              .collect(Collectors.toSet()));
          rolesStr += "}";
        }

        // ** logic

        StringBuilder endpointPath = new StringBuilder(controller.path());

        List<String> parametersCall = new ArrayList<>();
        parametersCall.add("ctx");
        List<String> parametersDecl = new ArrayList<>();

        List<String> pathParameters = new ArrayList<>();
        List<String> queryParameters = new ArrayList<>();

        String bodySchema = null;
        String returnOkSchema = null;
        String returnErrSchema = null;

        TypeMirror returnType = executableElement.getReturnType();
        TypeElement returnTypeElement = ProcessorUtil.asTypeElement(input.typeUtils(), returnType);

        if (returnTypeElement.getQualifiedName().toString().equals(Response.class.getName())) {
          var returnTypeGeneric = ProcessorUtil.getGenericTypes(returnType);
          TypeMirror returnTypeOk = returnTypeGeneric.get(0);
          TypeMirror returnTypeErr = returnTypeGeneric.get(1);

          switch (handlerResponseType) {
            case JSON:
              if (returnTypeOk.getKind() != TypeKind.DECLARED
                  || ProcessorUtil.getClassNameWithoutAnnotations(returnTypeOk)
                  .startsWith("java.lang.")) {
                Messager.error(executableElement,
                    "Endpoint method must return a valid success object");
                return;
              }
              if (returnTypeErr.getKind() != TypeKind.DECLARED
                  || ProcessorUtil.getClassNameWithoutAnnotations(returnTypeErr)
                  .startsWith("java.lang.")) {
                Messager.error(executableElement,
                    "Endpoint method must return a valid error object");
                return;
              }

              if (injector.generateDocumentation()) {
                // ok
                {
                  TypeElement typeOk = ProcessorUtil.asTypeElement(input.typeUtils(), returnTypeOk);
                  var schema = openApiUtil.classToSchema(schemaMap,
                      returnTypeOk, endpointPath.toString(), true, true);

                  returnOkSchema = ProcessorUtil.asSchemaName(returnTypeOk);
                }

                // err
                {
                  TypeElement typeErr = ProcessorUtil.asTypeElement(input.typeUtils(), returnTypeErr);
                  var schema = openApiUtil.classToSchema(schemaMap,
                      returnTypeErr, endpointPath.toString(), true, true);

                  returnErrSchema = ProcessorUtil.asSchemaName(returnTypeErr);
                }
              }
              break;
            case HTML:
            case STRING:
              if (!ProcessorUtil.getClassNameWithoutAnnotations(returnTypeOk)
                  .equals(String.class.getName()) || !ProcessorUtil.getClassNameWithoutAnnotations(
                  returnTypeErr).equals(String.class.getName())) {
                Messager.error(executableElement,
                    "Endpoint method must return a Response<String, String>");
                return;
              }
              break;
            case FILE:
              if (returnTypeOk.getKind() != TypeKind.DECLARED
                  || !ProcessorUtil.getClassNameWithoutAnnotations(returnTypeOk).equals(
                  FileResponse.class.getName())) {
                Messager.error(executableElement, "Endpoint response must return a FileResponse");
                return;
              }

              if (injector.generateDocumentation()) {
                // ok
                {
                  returnOkSchema = "@FileResponse";
                }
                // err
                {
                  TypeElement typeErr = ProcessorUtil.asTypeElement(input.typeUtils(), returnTypeErr);
                  var schema = openApiUtil.classToSchema(schemaMap,
                      returnTypeErr, endpointPath.toString(), true, true);

                  returnErrSchema = ProcessorUtil.asSchemaName(returnTypeErr);
                }
              }
          }
        } else if (returnTypeElement.getQualifiedName().toString()
            .equals(SuccessResponse.class.getName())) {
          var returnTypeGeneric = ProcessorUtil.getGenericTypes(returnType);
          TypeMirror returnTypeErr = returnTypeGeneric.get(0);

          switch (handlerResponseType) {
            case JSON:
              if (returnTypeErr.getKind() != TypeKind.DECLARED
                  || ProcessorUtil.getClassNameWithoutAnnotations(returnTypeErr)
                  .startsWith("java.lang.")) {
                Messager.error(executableElement,
                    "Endpoint method must return a valid error object");
                return;
              }
              // openapi

              if (injector.generateDocumentation()) {
                // ok
                {
                  returnOkSchema = "SuccessResponse";
                }
                // err
                {
                  TypeElement typeErr = ProcessorUtil.asTypeElement(input.typeUtils(), returnTypeErr);
                  var schema = openApiUtil.classToSchema(schemaMap,
                      returnTypeErr, endpointPath.toString(), true, true);

                  returnErrSchema = ProcessorUtil.asSchemaName(returnTypeErr);
                }
              }

              break;
            case HTML:
            case STRING:
              if (!ProcessorUtil.getClassNameWithoutAnnotations(returnTypeErr)
                  .equals(String.class.getName())) {
                Messager.error(executableElement,
                    "Endpoint method must return a SuccessResponse<String>");
                return;
              }
              break;
            case FILE:
              Messager.error(executableElement,
                  "File response is not supported in SuccessResponse");
              return;
          }
        } else {
          Messager.error(executableElement,
              "Endpoint method must return a Response or SuccessResponse");
          return;
        }

//        if (executableElement.getReturnType().getKind() != TypeKind.DECLARED
//            || !returnTypeElement.getQualifiedName().toString().equals(Response.class.getName())) {
//        }

        for (VariableElement variableElement : executableElement.getParameters()) {

          String nameParameter = variableElement.getSimpleName().toString();
          String typeParameter = ProcessorUtil.getClassNameWithoutAnnotations(
              variableElement.asType());
          String classParameter = typeParameter.split("<")[0];

          Body body = variableElement.getAnnotation(Body.class);
          if (body != null) {

            parametersCall.add(nameParameter);

            if (body.customType()) {
              if (!typeParameter.equals(String.class.getName())) {
                Messager.error(variableElement,
                    "Body parameter '%s' must be a String since is customType",
                    nameParameter);
                return;
              }
              parametersDecl.add(String.format("String %s = ctx.body();\n", nameParameter));

              if (injector.generateDocumentation()) {
                // openapi
                bodySchema = "CustomType";
              }

            } else {
              if (typeParameter.equals("io.javalin.http.UploadedFile")) {
                parametersDecl.add(
                    String.format("%s %s =com.quicklink.javalinfly.ContextExt.uploadedFile(ctx);\n",
                        typeParameter,
                        nameParameter));

                if (injector.generateDocumentation()) {
                  bodySchema = "$UploadedFile";
                }

              } else {
                parametersDecl.add(
                    String.format("%s %s = (%s) ctx.bodyAsClass(%s.class);\n", typeParameter,
                        nameParameter, typeParameter, classParameter));

                if (injector.generateDocumentation()) {
                  // openapi
                  TypeElement typeBodyName = ProcessorUtil.asTypeElement(input.typeUtils(),
                      variableElement.asType());
                  Schema schema = openApiUtil.classToSchema(schemaMap,
                      variableElement.asType(),
                      endpointPath.toString(), true, true);

                  bodySchema = typeBodyName.getSimpleName().toString();
                }
              }


            }


          }

          Path path = variableElement.getAnnotation(Path.class);
          if (path != null) {
            if (!typeParameter.equals(String.class.getName())) {
              Messager.error(variableElement, "Path parameter '%s' must be a String",
                  nameParameter);
              return;
            }

            endpointPath.append("/{").append(nameParameter).append("}");
            parametersCall.add(nameParameter);

            parametersDecl.add(String.format("String %s = ctx.pathParam(\"%s\");\n", nameParameter,
                nameParameter));

            pathParameters.add("\"" + nameParameter + "\"");
          }

          Query query = variableElement.getAnnotation(Query.class);
          if (query != null) {
            if (!typeParameter.equals(String.class.getName())) {
              Messager.error(variableElement, "Query parameter '%s' must be a String",
                  nameParameter);
              return;
            }

            parametersCall.add(nameParameter);

            parametersDecl.add(String.format("String %s = ctx.queryParam(\"%s\");\n", nameParameter,
                nameParameter));

            queryParameters.add("\"" + nameParameter + "\"");

            NotNull notNull = variableElement.getAnnotation(NotNull.class);
            if (notNull != null) {
              parametersDecl.add(
                  String.format("if(%s == null) throw new %s(\"%s\");\n", nameParameter,
                      QueryParamNotFoundException.class.getName(), nameParameter));
            }
          }
        }

        endpoints.add(
            "javalin.addHandler(HandlerType." + handlerType + ",config.pathPrefix + \""
                + endpointPath.toString() + "\", ctx -> {\n" +
                String.join("", parametersDecl) +
                String.format("var response = %s.%s(%s);\n", varDecl,
                    executableElement.getSimpleName(), String.join(",", parametersCall)) +
                responseType + "\n" +
                "\n} " + rolesStr + ");\n"
        );

        if (injector.generateDocumentation()) {
          openApiStatements.add(
              String.format(
                  "            openApiTranslator.addPath(%s, \"%s\", \"%s\", %s, \"%s\", %b, %s, %s, %s, %s, %s, ResponseType.%s);\n",
                  StringUtils.arrayToJavaCode(controller.tags()),
                  endpointPath.toString(),
                  handlerType,
                  StringUtils.arrayToJavaCode(handlerRoles),
                  summary,
                  deprecated,
                  pathParameters.isEmpty() ? "Collections.emptyList()"
                      : String.format("Arrays.asList(%s)", String.join(",", pathParameters)),
                  queryParameters.isEmpty() ? "Collections.emptyList()"
                      : String.format("Arrays.asList(%s)", String.join(",", queryParameters)),
                  /*body*/ bodySchema == null ? null : "\"" + bodySchema + "\"",
                  /* ok */ returnOkSchema == null ? null : "\"" + returnOkSchema + "\"",
                  /* err */ returnErrSchema == null ? null : "\"" + returnErrSchema + "\"",
                  handlerResponseType.name()
              ));
        }
      }
    }

    schemasEncoded = "{\"test\": 0}";
    if (injector.generateDocumentation()) {

      schemasEncoded = JsonUtils.get().serialize(schemaMap);
//        openApiStatements.add(0, "            openApiTranslator.decodeSchemas(\"" + schemasEncoded + "\");\n");
      openApiStatements.add(0,
          "            openApiTranslator.decodeSchemas(Vars.openApiSpec());\n");
    }
  }

  private @Nullable String registerConstructors(@NotNull TypeElement classTree) {
    for (var member : classTree.getEnclosedElements()) {
      if (!(member instanceof ExecutableElement)) {
        continue;
      }
      ExecutableElement executableElement = (ExecutableElement) member;

      if (!executableElement.getModifiers().contains(Modifier.PUBLIC)
          || executableElement.getKind() != ElementKind.CONSTRUCTOR) {
        continue;
      }

      if (executableElement.getParameters().isEmpty()) {
        String className = classTree.getQualifiedName().toString();
        String varDecl = ProcessorUtil.notCapitalize(className.replace(".", ""));
        this.handlersField.add(
            String.format("private %s %s = new %s();\n", className, varDecl, className));
        return varDecl;
      }

    }

    return null;
  }
}
