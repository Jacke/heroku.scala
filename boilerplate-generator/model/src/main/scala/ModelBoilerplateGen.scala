
import java.net.URLDecoder
import play.api.libs.json._
import scala.io.Source
import treehugger.forest._
import definitions._
import treehuggerDSL._

object ModelBoilerplateGen extends App {

  object sym {
    val ApiPackage = "com.heroku.platform.api.model"
    val ToJson = RootClass.newClass("ToJson")
    val FromJson = RootClass.newClass("FromJson")
    val Request = RootClass.newClass("Request")
    val RequestWithBody = RootClass.newClass("RequestWithBody")
    val ListRequest = RootClass.newClass("ListRequest")
  }

  def initialCap(s: String) = {
    val (f, l) = s.splitAt(1)
    s"${
      f.toUpperCase
    }$l"
  }

  def e(a: AnyRef) = System.err.println(a)

  def fieldType(typ: List[String]) = {
    val isOptional = typ.contains("null")
    val typez = typ.filter(_ != "null")
    if (typez.length == 1) {
      if (isOptional) (TYPE_OPTION(initialCap(typez(0))))
      else (TYPE_REF(initialCap(typez(0))))
    } else {
      throw new IllegalStateException("encountered type with more than one non null type value")
    }
  }

  def argType(typ: List[String]) = {
    val typez = typ.filter(_ != "null")
    if (typez.length == 1) {
      (TYPE_OPTION(initialCap(typez(0))))
    } else {
      throw new IllegalStateException("encountered type with more than one non null type value")
    }
  }

  def codez = {
    implicit val root = loadRoot
    root.resources.map(root.resource).map {
      resource =>
        (BLOCK(
          IMPORT("com.heroku.platform.api._"),
          IMPORT("com.heroku.platform.api.Request._"),
          model(resource),
          companion(resource, root),
          reqJson(resource),
          respJson(resource)
        ).inPackage(sym.ApiPackage): Tree)
    }
  }

  def model(resource: Resource)(implicit root: RootSchema) = {
    val params = resource.properties.map {
      case (k, Right(ref)) =>
        val typ = resource.resolveFieldRef(ref).fold({ oneOf => sys.error("Not expecting oneOf") }, { fieldDef => fieldType(fieldDef.`type`) })
        (PARAM(k, typ).tree)
      case (k, Left(nestedDef)) =>
        (PARAM(k, TYPE_REF("models." + resource.name + initialCap(k))).tree)
    }
    (CASECLASSDEF(resource.name) withParams params: Tree)
  }

  def companion(implicit resource: Resource, root: RootSchema) = {
    val name: String = resource.name

    val actionCaseClasses = resource.links.map {
      link =>
        val paramsMap = link.schema.map {
          schema =>
            schema.properties.map {
              case (k, typ) =>
                typ match {
                  case Right(fieldDef) => argsFromFieldDef(k, fieldDef)
                  case Left(Right(ref)) =>
                    resource.resolveFieldRef(ref).fold({ oneOf => argsFromOneOf(k, oneOf) }, { fieldDef => argsFromFieldDef(k, fieldDef) })
                  case Left(Left(oneOf)) => argsFromOneOf(k, oneOf)

                }

            }
        }.getOrElse(Seq.empty[(String, ValDef)])

        val hrefParamNames: Seq[String] = link.extractHrefParams
        val hrefParams = hrefParamNames.map(name => name -> (PARAM(name, StringClass)).tree)

        val params = (hrefParams ++ paramsMap).toSeq.map(_._2)
        val paramNames = paramsMap.toSeq.map(_._1)
        val extra = extraParams(link)

        link.title match {
          case "Create" => createAction(resource, paramNames, params, extra, link, hrefParamNames)
          case "List" => listAction(resource, paramNames, params, extra, link, hrefParamNames)
          case "Info" => infoAction(resource, paramNames, params, extra, link, hrefParamNames)
          case "Update" => updateAction(resource, paramNames, params, extra, link, hrefParamNames)
          case "Delete" => deleteAction(resource, paramNames, params, extra, link, hrefParamNames)
          case x => LIT(x)
        }
    }

    val modelCaseClasses = resource.links.map {
      link =>
        link.title match {
          case "Create" | "Update" => Some(bodyCaseClass(link))
          case _ => None
        }
    }.flatten

    OBJECTDEF(name) := BLOCK(
      actionCaseClasses ++
        Seq((OBJECTDEF("models") := BLOCK(modelCaseClasses ++ nestedModelClasses)))
    )
  }

  def extraParams(link: Link): Seq[ValDef] = {
    val defs: Seq[ValDef] = if (link.rel == "list") {
      Seq((PARAM("range", TYPE_OPTION("String")) := NONE))
    } else Seq.empty[ValDef]
    defs ++ Seq(PARAM("extraHeaders", TYPE_MAP("String", "String")) := NONE)
  }

  def nestedModelClasses(implicit resource: Resource, root: RootSchema) = {
    resource.properties.map {
      case (k, Right(ref)) => None
      case (k, Left(nestedDef)) => Some {
        val params = nestedDef.properties.map {
          case (name, ref) =>
            val typ = resource.resolveFieldRef(ref).fold({ oneOf => sys.error("Not expecting oneOf") }, { fieldDef => fieldType(fieldDef.`type`) })
            ((PARAM(name, typ) := NULL))
        }
        ((CASECLASSDEF(resource.name + initialCap(k)) withParams params): Tree)
      }
    }.flatten
  }

  def argsFromFieldDef(k: String, fieldDef: FieldDefinition)(implicit resource: Resource, root: RootSchema) = (k -> (PARAM(k, argType(fieldDef.`type`)) := NONE))

  def argsFromOneOf(k: String, oo: OneOf)(implicit resource: Resource, root: RootSchema) = {
    (k -> (PARAM(k, argType(List(resource.name + initialCap(k)))) := NONE))
  }

  def bodyCaseClass(link: Link)(implicit resource: Resource, root: RootSchema) = {
    val params = link.schema.map {
      schema =>
        schema.properties.map {
          case (k, typ) =>
            typ match {
              case Right(fieldDef) => argsFromFieldDef(k, fieldDef)
              case Left(Right(ref)) =>
                resource.resolveFieldRef(ref).fold({ oneOf => argsFromOneOf(k, oneOf) }, { fieldDef => argsFromFieldDef(k, fieldDef) })
              case Left(Left(oneOf)) => argsFromOneOf(k, oneOf)

            }

        }
    }.getOrElse(Seq.empty[(String, ValDef)]).map(_._2)

    ((CASECLASSDEF(s"${
      link.title
    }${resource.name}Body") withParams params.toIterable): Tree)
  }

  def toJson(model: String, typ: String) = {
    DEF(model + "ToJson", sym.ToJson TYPE_OF typ) withFlags (Flags.IMPLICIT)
  }

  def fromJson(model: String, typ: String) = {
    DEF(model + "FromJson", sym.FromJson TYPE_OF typ) withFlags (Flags.IMPLICIT)
  }

  def createAction(resource: Resource, paramNames: Iterable[String], params: Iterable[ValDef], extra: Iterable[ValDef], link: Link, hrefParams: Seq[String]) = {
    System.err.println(paramNames)
    (CASECLASSDEF(link.title) withParams params ++ extra withParents (sym.RequestWithBody TYPE_OF (s"models.Create${resource.name}Body", resource.name)) := BLOCK(
      expect("expect201"), endpoint(link.href, hrefParams), method("POST"),
      (VAL("body", s"models.Create${resource.name}Body") := (REF(s"models.Create${resource.name}Body") APPLY (paramNames.map(REF(_))))
      )): Tree)
  }

  def listAction(resource: Resource, paramNames: Iterable[String], params: Iterable[ValDef], extra: Iterable[ValDef], link: Link, hrefParams: Seq[String]) = {
    (CASECLASSDEF(link.title) withParams params ++ extra withParents (sym.ListRequest TYPE_OF (resource.name)) := BLOCK(
      endpoint(link.href, hrefParams), method("GET"),
      (DEF("nextRequest", (sym.ListRequest TYPE_OF (resource.name))) withParams ((VAL("nextRange", "String"))) := THIS DOT "copy" APPLY (REF("range") := SOME(REF("nextRange"))))))
  }

  def infoAction(resource: Resource, paramNames: Iterable[String], params: Iterable[ValDef], extra: Iterable[ValDef], link: Link, hrefParams: Seq[String]) = {
    (CASECLASSDEF(link.title) withParams params ++ extra withParents (sym.Request TYPE_OF (resource.name)) := BLOCK(
      expect("expect200"), endpoint(link.href, hrefParams), method("GET")): Tree)
  }

  def updateAction(resource: Resource, paramNames: Iterable[String], params: Iterable[ValDef], extra: Iterable[ValDef], link: Link, hrefParams: Seq[String]) = {
    (CASECLASSDEF(link.title) withParams params ++ extra withParents (sym.RequestWithBody TYPE_OF (s"models.Update${resource.name}Body", resource.name)) := BLOCK(
      expect("expect200"), endpoint(link.href, hrefParams), method("PUT"),
      (VAL("body", s"models.Update${resource.name}Body") := (REF(s"models.Update${resource.name}Body") APPLY (paramNames.map(REF(_)))))
    ): Tree)
  }

  def deleteAction(resource: Resource, paramNames: Iterable[String], params: Iterable[ValDef], extra: Iterable[ValDef], link: Link, hrefParams: Seq[String]) = {
    (CASECLASSDEF(link.title) withParams params ++ extra withParents (sym.Request TYPE_OF (resource.name)) := BLOCK(
      expect("expect200"), endpoint(link.href, hrefParams), method("DELETE")): Tree)
  }

  def expect(exRef: String) = (VAL("expect", TYPE_SET(IntClass)) := REF(exRef))

  def endpoint(endRef: String, params: Seq[String]) = {
    val endLit = endRef.replaceAll("""\{.+\}""", """\%s""")
    if (params.isEmpty) (VAL("endpoint", StringClass) := LIT(endLit))
    else (VAL("endpoint", StringClass) := LIT(endLit) DOT "format" APPLY (params.toSeq.map(p => REF(p)): _*))
  }

  def method(methRef: String) = (VAL("method", StringClass) := REF(methRef))

  def reqJson(resource: Resource)(implicit root: RootSchema) = {

    val modelToJsons = resource.links.map {
      link =>
        link.title match {
          case "Create" | "Update" =>
            val to = s"${link.title}${resource.name}Body"
            Some(toJson(to, s"models.${to}"))
          case _ => None
        }
    }.flatten

    val nesteds = resource.properties.map {
      case (k, Right(ref)) => None
      case (k, Left(nestedDef)) =>
        Some(toJson(resource.name + initialCap(k), resource.name + initialCap(k)))
    }.flatten

    TRAITDEF(s"${resource.name}RequestJson") := BLOCK(
      modelToJsons.toSeq ++ nesteds.toSeq
    )
  }

  def respJson(resource: Resource)(implicit root: RootSchema) = {
    val resps = resource.properties.map {
      case (k, Right(ref)) => None
      case (k, Left(nestedDef)) =>
        Some(fromJson(resource.name + initialCap(k), resource.name + initialCap(k)))
    }.flatten ++ Seq(fromJson(resource.name, resource.name), fromJson(s"List${resource.name}", s"List[${resource.name}]"))
    TRAITDEF(s"${resource.name}ResponseJson") := BLOCK(resps)
  }

  implicit def fmtResource: Format[Resource] = Json.format[Resource]

  implicit def fmtAction: Format[Link] = Json.format[Link]

  implicit def fmtSchema: Format[Schema] = Json.format[Schema]

  implicit def re[L, R](implicit l: Format[L], r: Format[R]): Format[Either[L, R]] = Format(Reads(
    js =>
      JsSuccess(js.validate[R].fold({
        er =>
          js.validate[L].fold({
            el => sys.error(er.toString + el.toString + Json.prettyPrint(js))
          }, {
            s => Left[L, R](s)
          })
      }, {
        r => Right[L, R](r)
      }))
  ), Writes {
    case Right(ar) => r.writes(ar)
    case Left(al) => l.writes(al)
  })

  implicit def fd: Format[FieldDefinition] = Json.format[FieldDefinition]

  implicit def fr: Format[Ref] = Json.format[Ref]

  implicit def fo: Format[OneOf] = Json.format[OneOf]

  implicit def fn: Format[NestedDef] = Json.format[NestedDef]

  implicit def fmtRootSchema: Format[RootSchema] = Json.format[RootSchema]

  def fileToString(schemaFile: String) = Source.fromFile(schemaFile).foldLeft(new StringBuilder) {
    case (b, c) => b.append(c)
  }.toString

  def schemaText(name: String): String = {
    val schemaFile = s"api/src/main/resources/schema/$name.json"
    fileToString(schemaFile)
  }

  //$ref :  #definitions/something or schema/foo#definitions/something
  case class Ref(`$ref`: String) {
    def path = `$ref`

    def isLocal: Boolean = path.startsWith("#")

    def schema: Option[String] = {
      println(path)
      if (isLocal) None
      else Some(path.substring(0, path.indexOf("#")).drop("/schema/".length))
    }

    def definition: String = if (isLocal) path.substring("#/definitions/".length)
    else path.substring(path.indexOf("#")).drop("#/definitions/".length)
  }
  //these are the fields on either a top level or inner object or schema, which hang off definitions and are resolved by $ref
  case class FieldDefinition(description: String, example: Option[JsValue], format: Option[String], readOnly: Option[Boolean], `type`: List[String])

  //these map to "inner" objects inside a top level object, like region inside app
  case class NestedDef(properties: Map[String, Ref])

  //Describes the body of a PUT/POST in a Link
  case class Schema(properties: Map[String, Either[Either[OneOf, Ref], FieldDefinition]])

  //Endpoint
  case class Link(title: String, rel: String, href: String, method: String, schema: Option[Schema]) {
    val refEx = """\{(.+)\}""".r
    val encEx = """\((.+)\)""".r

    def extractHrefParams(implicit root: RootSchema, res: Resource): Seq[String] = {
      href.split('/').map {
        //decode urlencoded $refs in the href
        case refEx(encEx(ref)) => Some(Ref(URLDecoder.decode(ref))).map(r => res.resolveFieldRef(r).fold(o => r.schema.getOrElse(res.name.toLowerCase) + "_" + o.orFields, f => r.schema.getOrElse(res.name.toLowerCase) + r.definition))
        case refEx(ref) => Some(Ref(ref)).map(r => res.resolveFieldRef(r).fold(o => o.orFields, f => r.definition))
        case _ => None
      }.toSeq.flatten
    }
  }

  //in our case it is either the id or friendly id so should be size 2
  case class OneOf(oneOf: List[Ref]) {
    def orFields = oneOf match {
      case one :: two :: Nil => s"${one.definition}_or_${two.definition}"
      case _ => sys.error(s"OneOf had ${oneOf.length} items. Expected 2")
    }
  }

  //schema for a endpoint/object type
  case class Resource(description: String, id: String, title: String, definitions: Map[String, Either[OneOf, FieldDefinition]], links: List[Link], properties: Map[String, Either[NestedDef, Ref]]) {
    def resolveFieldRef(ref: Ref)(implicit root: RootSchema): Either[OneOf, FieldDefinition] = {
      val res: Resource = ref.schema.map(resource => root.resource(resource)).getOrElse(this)
      res.definitions.get(ref.definition).getOrElse(sys.error(s"cant resolve ${ref} -> ${ref.definition} from $res"))
    }
    def name = initialCap(id.drop("schema/".length))
  }

  //root schema.json
  case class RootSchema(description: String, properties: Map[String, Map[String, String]], title: String) {

    val resourceMap = new collection.mutable.HashMap[String, Resource]

    def resources = properties.keys

    def resource(name: String): Resource = resourceMap.getOrElseUpdate(name, loadResource(name))

    def loadResource(name: String): Resource = {
      val ref = properties(name)("$ref")
      // "/schema/app#"
      val schema = ref.drop("/schema".length).dropRight("#".length)
      val text = schemaText(schema)
      Json.parse(text).validate[Resource].fold(
        {
          e => sys.error(e.toString)
        }, {
          res => res
        }
      )
    }

    def loadAll = resources.map(resource)

  }

  def loadRoot = Json.parse(fileToString("api/src/main/resources/schema.json")).as[RootSchema]

  codez.foreach(t => println(treeToString(t)))

}
