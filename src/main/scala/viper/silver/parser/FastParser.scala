// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silver.parser

import java.net.URL
import java.nio.file.{Files, Path, Paths}

import scala.util.parsing.input.{NoPosition, Position}
import fastparse.core.Parsed
import fastparse.all
import viper.silver.ast.{LineCol, SourcePosition}
import viper.silver.FastPositions
import viper.silver.ast.utility.rewriter.{ContextA, PartialContextC, StrategyBuilder}
import viper.silver.parser.Transformer.ParseTreeDuplicationError
import viper.silver.plugin.SilverPluginManager
import viper.silver.verifier.{ParseError, ParseReport, ParseWarning}

import scala.collection.mutable

case class ParseException(msg: String, pos: scala.util.parsing.input.Position) extends Exception

case class SuffixedExpressionGenerator[E <: PExp](func: PExp => E) extends (PExp => PExp) with FastPositioned {
  override def apply(v1: PExp): E = func(v1)
}

object FastParser extends PosParser[Char, String] {

  /* When importing a file from standard library, e.g. `include <inc.vpr>`, the file is expected
   * to be located in `resources/${standard_import_directory}`, e.g. `resources/import/inv.vpr`.
   */
  val standard_import_directory = "import"

  var _lines: Array[Int] = null

  def parse(s: String, f: Path, plugins: Option[SilverPluginManager] = None) = {
    _file = f.toAbsolutePath
    val lines = s.linesWithSeparators
    _lines = lines.map(_.length).toArray

    // Strategy to handle imports
    // Idea: Import every import reference and merge imported methods, functions, imports, .. into current program
    //       iterate until no new imports are present.
    //       To import each file at most once the absolute path is normalized (removes redundancies).
    //       For standard import the path relative to the import folder (in resources) is normalized and used.
    //       (normalize a path is a purely syntactic operation. if sally were a symbolic link removing sally/.. might
    //       result in a path that no longer locates the intended file. toRealPath() might be an alternative)

    def resolveImports(p: PProgram) = {
        val localsToImport = new mutable.ArrayBuffer[Path]()
        val localImportStatements = new mutable.HashMap[Path, PLocalImport]()
        val standardsToImport = new mutable.ArrayBuffer[Path]()
        val standardImportStatements = new mutable.HashMap[Path, PStandardImport]()

        // assume p is a program from the user space (local).
        val filePath = f.toAbsolutePath.normalize()
        localsToImport.append(filePath)

        var macros = p.macros
        var domains = p.domains
        var fields = p.fields
        var functions = p.functions
        var methods = p.methods
        var predicates = p.predicates
        var extensions = p.extensions
        var errors = p.errors

        def appendNewImports(imports: Seq[PImport], current: Path, fromLocal: Boolean) {
          for (ip <- imports) {
            ip match {
              case localImport: PLocalImport if fromLocal =>
                val localPath = current.resolveSibling(localImport.file).normalize()
                if(!localsToImport.contains(localPath)){
                  localsToImport.append(localPath)
                  localImportStatements.update(localPath, localImport)
                }
              case localImport: PLocalImport if !fromLocal =>
                // local import get transformed to standard imports
                val localPath = current.resolveSibling(localImport.file).normalize()
                if (!standardsToImport.contains(localPath)) {
                  standardsToImport.append(localPath)
                  standardImportStatements.update(localPath, PStandardImport(localPath.toString))
                }
              case standardImport: PStandardImport =>
                val standardPath = Paths.get(standardImport.file).normalize()
                if(!standardsToImport.contains(standardPath)){
                  standardsToImport.append(standardPath)
                  standardImportStatements.update(standardPath, standardImport)
                }
            }
          }
        }

        def appendNewProgram(newProg: PProgram) {
          macros ++= newProg.macros
          domains ++= newProg.domains
          fields ++= newProg.fields
          functions ++= newProg.functions
          methods ++= newProg.methods
          predicates ++= newProg.predicates
          extensions ++= newProg.extensions
          errors ++= newProg.errors
        }

        appendNewImports(p.imports, filePath, true)

        // resolve imports from imported programs
        var i = 1 // localsToImport
        var j = 0 // standardsToImport
        while (i < localsToImport.length || j < standardsToImport.length) {
          // at least one local or standard import has not yet been resolved
          if (i < localsToImport.length){
            // import a local file

            val current = localsToImport(i)
            val newProg = importLocal(current, localImportStatements(current), plugins)

            appendNewProgram(newProg)
            appendNewImports(newProg.imports, current, true)
            i += 1
          }else{
            // no more local imports
            // import a standard file
            val current = standardsToImport(j)
            val newProg = importStandard(current, standardImportStatements(current), plugins)

            appendNewProgram(newProg)
            appendNewImports(newProg.imports, current, false)
            j += 1
          }
        }
      PProgram(Seq(), macros, domains, fields, functions, predicates, methods, extensions, errors)
    }


    try {
      val rp = RecParser(f).parses(s)
      rp match {
        case Parsed.Success(program@PProgram(_, _, _, _, _, _, _, _, errors), e) =>
          val importedProgram = resolveImports(program) // Import programs
          val expandedProgram = expandDefines(importedProgram) // Expand macros
          Parsed.Success(expandedProgram, e)
        case _ => rp
      }
    }
    catch {
      case ParseException(msg, pos) =>
        var line = 0
        var column = 0
        if (pos != null) {
          line = pos.line
          column = pos.column
        }
        ParseError(msg, SourcePosition(_file, line, column))
    }
  }

  case class RecParser(file: Path) {

    def parses(s: String) = {
      fastparser.parse(s)
    }
  }


  val White = PWrapper {
    import fastparse.all._

    NoTrace((("/*" ~ (!StringIn("*/") ~ AnyChar).rep ~ "*/") | ("//" ~ CharsWhile(_ != '\n').? ~ ("\n" | End)) | " " | "\t" | "\n" | "\r").rep)
  }

  import fastparse.noApi._

  import White._

  // Actual Parser starts from here
  def identContinues = CharIn('0' to '9', 'A' to 'Z', 'a' to 'z', "$_")

  def keyword(check: String) = check ~~ !identContinues

  def parens[A](p: fastparse.noApi.Parser[A]) = "(" ~ p ~ ")"

  def angles[A](p: fastparse.noApi.Parser[A]) = "<" ~ p ~ ">"

  def quoted[A](p: fastparse.noApi.Parser[A]) = "\"" ~ p ~ "\""

  def foldPExp[E <: PExp](e: PExp, es: Seq[SuffixedExpressionGenerator[E]]): E =
    es.foldLeft(e) { (t, a) =>
      val result = a(t)
      FastPositions.setStart(result, t.start)
      FastPositions.setFinish(result, a.finish)
      result
    }.asInstanceOf[E]

  def isFieldAccess(obj: Any) = {
    obj.isInstanceOf[PFieldAccess]
  }

  /**
    * Function that parses a file and converts it into a program
    *
    * @param buffer Buffer to read file from
    * @param path Path of the file to be imported
    * @param importStmt Import statement.
    * @return `PProgram` node corresponding to the imported program.
    */
  def importProgram(buffer: Array[String], path: Path, importStmt: PImport, plugins: Option[SilverPluginManager]): PProgram = {

    val imported_source = buffer.mkString("\n") + "\n"
    val transformed_source = if (plugins.isDefined){
      plugins.get.beforeParse(imported_source, isImported = true) match {
        case Some(transformed) => transformed
        case None => throw ParseException(s"Plugin failed: ${plugins.get.errors.map(_.toString).mkString(", ")}", importStmt.start)
      }
    } else {
      imported_source
    }
    val p = RecParser(path).parses(transformed_source)
    p match {
      case fastparse.core.Parsed.Success(prog, _) => prog
      case fail @ fastparse.core.Parsed.Failure(_, index, extra) =>
        val msg = all.ParseError(fail).getMessage
        val (line, col) = LineCol(extra.input, index)
        throw ParseException(s"Expected $msg", FilePosition(path, line, col))
    }
  }

  /**
    * Opens (and closes) standard file to be imported, parses it and converts it into a program.
    * Standard files are located in the resources inside a "import" folder.
    *
    * @param path Path of the file to be imported
    * @param importStmt Import statement.
    * @return `PProgram` node corresponding to the imported program.
    */
  def importStandard(path: Path, importStmt: PStandardImport, plugins: Option[SilverPluginManager]): PProgram = {
    /* Prefix the standard library import (`path`) with the directory in which standard library
     * files are expected (`standard_import_directory`). The result is a OS-specific path, e.g.
     * "import\my\stdlib.vpr".
     */
    val relativeImportPath = Paths.get(standard_import_directory, path.toString)

    /* Creates a corresponding relative URL, e.g. "file://import/my/stdlib.vpr" */
    val relativeImportUrl = new URL(new URL("file:"), relativeImportPath.toString)

    /* Extract the path component only, e.g. "import/my/stdlib.vpr" */
    val relativeImportStr = relativeImportUrl.getPath

    /* Resolve the import using the specified class loader. Could point into the local file system
     * or into a jar file. The latter case requires `relativeImportStr` to be a valid URL (which
     * rules out Windows paths).
     */
    val source = scala.io.Source.fromResource(relativeImportStr, getClass.getClassLoader)

    // nested try-catch block because source.close() in finally could also cause a NullPointerException
    val buffer =
      try {
        try {
          source.getLines.toArray
        } catch {
          case e@(_: RuntimeException | _: java.io.IOException) =>
            throw ParseException(s"could not import file ($e)", FastPositions.getStart(importStmt))
        } finally {
          source.close()
        }
      } catch {
        case e: java.lang.NullPointerException =>
          throw ParseException(s"""file <$path> does not exist""", FastPositions.getStart(importStmt))
      }

    //scala.io.Source.fromInputStream(getClass.getResourceAsStream("/import/"+ path.toString))
    importProgram(buffer, path, importStmt, plugins)
  }

  /**
    * Opens (and closes) local file to be imported, parses it and converts it into a program.
    *
    * @param path Path of the file to be imported
    * @param importStmt Import statement.
    * @return `PProgram` node corresponding to the imported program.
    */
  def importLocal(path: Path, importStmt: PImport, plugins: Option[SilverPluginManager]): PProgram = {
    if (java.nio.file.Files.notExists(path)) {
      throw ParseException(s"""file "$path" does not exist""", FastPositions.getStart(importStmt))
    }

    _file = path
    val source = scala.io.Source.fromInputStream(Files.newInputStream(path))

    val buffer = try {
      source.getLines.toArray
    } catch {
      case e@(_: RuntimeException | _: java.io.IOException) =>
        throw ParseException(s"""could not import file ($e)""", FastPositions.getStart(importStmt))
    } finally {
      source.close()
    }

    importProgram(buffer, path, importStmt, plugins)
  }

  /**
    * Expands the macros of a PProgram
    *
    * @param p PProgram with macros to be expanded
    * @return PProgram with expanded macros
    */
  def expandDefines(p: PProgram): PProgram = {
    val globalMacros = p.macros

    // Collect names to check for and avoid clashes
    val globalNamesWithoutMacros: Set[String] = (
         p.domains.map(_.idndef.name).toSet
      ++ p.functions.map(_.idndef.name).toSet
      ++ p.predicates.map(_.idndef.name).toSet
      ++ p.methods.map(_.idndef.name).toSet
    )

    // Check if all macros names are unique
    var uniqueMacroNames = new mutable.HashMap[String, Position]()
    for (define <- globalMacros) {
      if (uniqueMacroNames.contains(define.idndef.name)) {
        throw ParseException(s"Another macro named '${define.idndef.name}' already " +
          s"exists at ${uniqueMacroNames(define.idndef.name)}", define.start)
      } else {
        uniqueMacroNames += ((define.idndef.name, define.start))
      }
    }

    // Check if macros names aren't already taken by other identifiers
    for (name <- globalNamesWithoutMacros) {
      if (uniqueMacroNames.contains(name)) {
        throw ParseException(s"The macro name '$name' has already been used by another identifier", uniqueMacroNames(name))
      }
    }

    // Check if macros are defined in the right place
    case class InsideMagicWandContext(inside: Boolean = false)
    StrategyBuilder.ContextVisitor[PNode, InsideMagicWandContext]({case (_, _) => ()}, InsideMagicWandContext(), {
      case (_: PPackageWand, c) => c.copy(true)
      case (d: PDefine, c) if c.inside => throw ParseException("Macros cannot be defined inside magic wands proof scripts", d.start)
    }).execute(p)

    // Check if all macro parameters are used in the body
    def allParametersUsedInBody(define: PDefine): Seq[ParseWarning] = {
      val parameters = define.parameters.getOrElse(Seq.empty[PIdnDef]).map(_.name).toSet
      val freeVars = mutable.Set.empty[String]

      case class BoundedVars(boundedVars: Set[String] = Set())
      StrategyBuilder.ContextVisitor[PNode, BoundedVars]((_, _) => (), BoundedVars(), {
        case (id: PIdnUse, ctx) => freeVars ++= Set(id.name) -- ctx.boundedVars
                                   ctx
        case (q @ (_: PForall | _: PExists), ctx) => ctx.copy(boundedVars = ctx.boundedVars |
                                                              q.asInstanceOf[PQuantifier].vars.map(_.idndef.name).toSet)
      }).execute(define)

      val nonUsedParameter = parameters -- freeVars

      if (nonUsedParameter.nonEmpty) {
        Seq(ParseWarning(s"In macro ${define.idndef.name}, the following parameters were defined but not used: " +
          s"${nonUsedParameter.mkString(", ")} ", SourcePosition(_file, define.start.line, define.start.column)))
      }
      else
        Seq()
    }

    var warnings = Seq.empty[ParseWarning]

    warnings = (warnings /: globalMacros)(_ ++ allParametersUsedInBody(_))

    globalMacros.foreach(allParametersUsedInBody(_))

    // Expand defines
    val domains =
      p.domains.map(domain => {
        doExpandDefines[PDomain](globalMacros, domain, p)
      })

    val functions =
      p.functions.map(function => {
        doExpandDefines(globalMacros, function, p)
      })

    val predicates =
      p.predicates.map(predicate => {
        doExpandDefines(globalMacros, predicate, p)
      })

    def linearizeMethod(method: PMethod): PMethod = {
      def linearizeSeqOfNestedStmt(pseqn: PSeqn): Seq[PStmt] = {
        var stmts = Seq.empty[PStmt]
        pseqn.ss.foreach {
          case s: PSeqn => stmts = stmts ++ linearizeSeqOfNestedStmt(s)
          case v => stmts = stmts :+ v
        }
        stmts
      }

      val body = method.body match {
        case Some(s: PSeqn) => Some(PSeqn(linearizeSeqOfNestedStmt(s)))
        case v => v
      }

      if (body != method.body)
        PMethod(method.idndef, method.formalArgs, method.formalReturns, method.pres, method.posts, body)
      else
        method
    }

    val methods = p.methods.map(method => {
      // Collect local macro definitions
      val localMacros = method.deepCollect { case n: PDefine => n }

      warnings = (warnings /: localMacros)(_ ++ allParametersUsedInBody(_))

      // Remove local macro definitions from method
      val methodWithoutMacros =
        if (localMacros.isEmpty)
          method
        else
          method.transform { case mac: PDefine => PSkip().setPos(mac) }()

      linearizeMethod(doExpandDefines(localMacros ++ globalMacros, methodWithoutMacros, p))
    })

    PProgram(p.imports, p.macros, domains, p.fields, functions, predicates, methods, p.extensions, p.errors ++ warnings)
  }


  /**
    * Expand all macro calls in a subtree of the program's AST
    *
    * @param macros   All macros that can be called in the subtree
    * @param subtree  The root the subtree whose macro calls will be expanded
    * @param program  Root of the AST representing the program which includes 'subtree'
    * @tparam T       Type parameter of 'subtree' and return type, which is a subtype of PNode
    * @return         The same subtree with all macro calls expanded
    */
  def doExpandDefines[T <: PNode] (macros: Seq[PDefine], subtree: T, program: PProgram): T = {

    // Store the replacements from normal variable to freshly generated variable
    val renamesMap = mutable.Map.empty[String, String]
    var scopeAtMacroCall = Set.empty[String]
    val scopeOfExpandedMacros = mutable.Set.empty[String]

    def scope: Set[String] = scopeAtMacroCall ++ scopeOfExpandedMacros

    case class ReplaceContext(paramToArgMap: Map[String, PExp] = Map.empty,
                              boundVars: Set[String] = Set.empty)

    // Handy method to get a macro from its name string
    def getMacroByName(name: String): PDefine = macros.find(_.idndef.name == name) match {
      case Some(mac) => mac
      case None => throw ParseException(s"Macro " + name + " used but not present in scope", FastPositions.getStart(name))
    }

    // Check if a string is a valid macro name
    def isMacro(name: String): Boolean = macros.exists(_.idndef.name == name)

    // The position of every node inside the macro is the position where the macro is "called"
    def adaptPositions(body: PNode, f: FastPositioned): Unit = {
      val adapter = StrategyBuilder.SlimVisitor[PNode] {
        n => {
          FastPositions.setStart(n, f.start, force = true)
          FastPositions.setFinish(n, f.finish, force = true)
        }
      }
      adapter.execute[PNode](body)
    }

    object getFreshVarName {
      private val namesToNumbers = mutable.Map.empty[String, Int]

      def apply(name: String): String = {
        var number = namesToNumbers.get(name) match {
          case Some(number) => number + 1
          case None => 0
        }

        val freshVarName = (name: String, number: Int) => s"$name$$$number"

        while (scope.contains(freshVarName(name, number)))
          number += 1

        namesToNumbers += name -> number

        freshVarName(name, number)
      }
    }

    // Create a map that maps the formal parameters to the actual arguments of a macro call
    def mapParamsToArgs(params: Seq[PIdnDef], args: Seq[PExp]): Map[String, PExp] = {
      params.map(_.name).zip(args).toMap
    }

    /* Abstraction over several possible `PNode`s that can represent macro applications */
    case class MacroApp(name: String, arguments: Seq[PExp], node: PNode)

    val matchOnMacroCall: PartialFunction[PNode, MacroApp] = {
      case app: PMacroRef => MacroApp(app.idnuse.name, Nil, app)
      case app: PMethodCall if isMacro(app.method.name) => MacroApp(app.method.name, app.args, app)
      case app: PCall if isMacro(app.func.name) => MacroApp(app.func.name, app.args, app)
      case app: PIdnUse if isMacro(app.name) => MacroApp(app.name, Nil, app)
    }

    def detectCyclicMacros(start: PNode, seen: Set[String]): Unit = {
      start.visit(
        matchOnMacroCall.andThen { case MacroApp(name, _, _) =>
          if (seen.contains(name)) {
            val position =
              macros.find(_.idndef.name == name)
                    .fold[Position](NoPosition)(FastPositions.getStart)

            throw ParseException("Recursive macro declaration found: " + name, position)
          } else {
            detectCyclicMacros(getMacroByName(name).body, seen + name)
          }
        }
      )
    }

    detectCyclicMacros(subtree, Set.empty)

    // Strategy to rename variables declared in macro's body if their names are already used in
    // the scope where the macro is being expanded, avoiding name clashes (hygienic macro expansion)
    val renamer = StrategyBuilder.Slim[PNode]({

      // Variable declared: either local or bound
      case varDecl: PIdnDef =>

        // If variable name is already used in scope
        if (scope.contains(varDecl.name)) {

          // Rename variable
          val freshVarName = getFreshVarName(varDecl.name)

          // Update scope
          scopeOfExpandedMacros += freshVarName
          renamesMap += varDecl.name -> freshVarName

          // Create a variable with new name to substitute the previous one
          val freshVarDecl = PIdnDef(freshVarName)
          adaptPositions(freshVarDecl, varDecl)
          freshVarDecl
        } else {

          // Update scope
          scopeOfExpandedMacros += varDecl.name

          // Return the same variable
          varDecl
        }

      // Variable used: update variable's name according to its declaration
      // Macro's parameters are not renamed, since they will be replaced by
      // their respective arguments in the following steps (by replacer)
      case varUse: PIdnUse if renamesMap.contains(varUse.name) =>
        PIdnUse(renamesMap(varUse.name))

    })

    // Strategy to replace macro's parameters by their respective arguments
    val replacer = StrategyBuilder.Context[PNode, ReplaceContext]({

      // Variable use: macro parameters are replaced by their respective argument expressions
      case (varUse: PIdnUse, ctx) if ctx.c.paramToArgMap.contains(varUse.name) &&
                                     !ctx.c.boundVars.contains(varUse.name) =>
        ctx.c.paramToArgMap(varUse.name)

    }, ReplaceContext())

    val replacerContextUpdater: PartialFunction[(PNode, ReplaceContext), ReplaceContext] = {
      case (ident: PIdnUse, ctx) if ctx.paramToArgMap.contains(ident.name) =>
        /* Matches case "replace parameter with argument" above. Having replaced a parameter
         * with an argument, no further substitutions should be carried out for the
         * plugged-in argument.
         */
        ctx.copy(paramToArgMap = ctx.paramToArgMap.empty)

      case (q @ (_: PForall | _: PExists), ctx) => ctx.copy(boundVars = ctx.boundVars |
                                                            q.asInstanceOf[PQuantifier].vars.map(_.idndef.name).toSet)
    }

    // Replace variables in macro body, adapt positions correctly (same line number as macro call)
    def replacerOnBody(body: PNode, paramToArgMap: Map[String, PExp], pos: FastPositioned): PNode = {
      /* TODO: It would be best if the context updater function were passed as another argument
       *       to the replacer above. That is already possible, but when the replacer is executed
       *       and an initial context is passed, that initial context's updater function (which
       *       defaults to "never update", if left unspecified) replaces the updater function that
       *       was initially passed to renamer.
       */

      // Rename locally bound variables in macro's body
      val bodyWithRenamedVars = renamer.execute[PNode](body)
      adaptPositions(bodyWithRenamedVars, pos)

      // Create context
      val context = new PartialContextC[PNode, ReplaceContext](ReplaceContext(paramToArgMap), replacerContextUpdater)

      // Replace macro's call arguments for every occurrence of its respective parameters in the body
      val bodyWithReplacedParams = replacer.execute[PNode](bodyWithRenamedVars, context)
      adaptPositions(bodyWithReplacedParams, pos)

      // Return expanded macro's body
      bodyWithReplacedParams
    }

    def ExpandMacroIfValid(macroCall: PNode, ctx: ContextA[PNode]): PNode = {
      matchOnMacroCall.andThen {
        case MacroApp(name, arguments, call) =>
          val macroDefinition = getMacroByName(name)
          val parameters = macroDefinition.parameters.getOrElse(Nil)
          val body = macroDefinition.body
          val pos = FastPositions.getStart(call)

          if (arguments.length != parameters.length)
            throw ParseException("Number of macro arguments does not match", pos)

          (call, body) match {
            case (_: PStmt, _: PExp) =>
              throw ParseException("Expression macro used in statement position", pos)
            case (_: PExp, _: PStmt) =>
              throw ParseException("Statement macro used in expression position", pos)
            case _ =>
          }

          /* TODO: The current unsupported position detection is probably not exhaustive.
           *       Seems difficult to concisely and precisely match all (il)legal cases, however.
           */
          (ctx.parent, body) match {
            case (PAccPred(loc, _), _) if (loc eq call) && !body.isInstanceOf[PLocationAccess] =>
              throw ParseException("Macro expansion would result in invalid code...\n...occurs in position where a location access is required, but the body is of the form:\n" + body.toString, pos)
            case (_: PCurPerm, _) if !body.isInstanceOf[PLocationAccess] =>
              throw ParseException("Macro expansion would result in invalid code...\n...occurs in position where a location access is required, but the body is of the form:\n" + body.toString, pos)
            case _ => /* All good */
          }

          try {
            scopeAtMacroCall = NameAnalyser().namesInScope(program, Some(macroCall))
            arguments.foreach(
              StrategyBuilder.SlimVisitor[PNode] {
                case id: PIdnDef => scopeAtMacroCall += id.name
                case _ =>
              }.execute[PNode](_)
            )
            renamesMap.clear
            replacerOnBody(body, mapParamsToArgs(parameters, arguments), call)
          } catch {
            case problem: ParseTreeDuplicationError =>
              throw ParseException("Macro expansion would result in invalid code (encountered ParseTreeDuplicationError:)\n" + problem.getMessage, pos)
          }
      }.applyOrElse(macroCall, (_: PNode) => macroCall)
    }

    // Strategy that checks if the macro calls are valid and expands them.
    // Requires that macro calls are acyclic
    val expander = StrategyBuilder.Ancestor[PNode] {

      // Handles macros on the left hand-side of assignments
      case (PMacroAssign(call, exp), ctx) =>
        if (!isMacro(call.opName))
          throw ParseException("The only calls that can be on the left-hand side of an assignment statement are calls to macros", FastPositions.getStart(call))

        val body = ExpandMacroIfValid(call, ctx)

        // Check if macro's body can be the left-hand side of an assignment and,
        // if that's the case, add it in a corresponding assignment statement
        body match {
          case fa: PFieldAccess =>
            val node = PFieldAssign(fa, exp)
            adaptPositions(node, fa)
            node
          case _ => throw ParseException("The body of this macro is not a suitable left-hand side for an assignment statement", FastPositions.getStart(call))
        }

      // Handles all other calls to macros
      case (node, ctx) => ExpandMacroIfValid(node, ctx)

    }.recurseFunc {
      /* Don't recurse into the PIdnUse of nodes that themselves could represent macro
       * applications. Otherwise, the expansion of nested macros will fail due to attempting
       * to construct invalid AST nodes.
       * Recursing into such PIdnUse nodes caused Silver issue #205.
       */
      case PMacroRef(_) => Seq.empty
      case PMethodCall(targets, _, args) => Seq(targets, args)
      case PCall(_, args, typeAnnotated) => Seq(args, typeAnnotated)
    }.repeat

    try {
      expander.execute[T](subtree)
    } catch {
      case problem: ParseTreeDuplicationError =>
        throw ParseException("Macro expansion would result in invalid code (encountered ParseTreeDuplicationError:)\n" + problem.getMessage, problem.original.start)
    }
  }

  /** The file we are currently parsing (for creating positions later). */
  def file: Path = _file


  val LHS_OLD_LABEL = "lhs"

  lazy val keywords = Set("result",
    // types
    "Int", "Perm", "Bool", "Ref", "Rational",
    // boolean constants
    "true", "false",
    // null
    "null",
    // preamble importing
    "import",
    // declaration keywords
    "method", "function", "predicate", "program", "domain", "axiom", "var", "returns", "field", "define",
    // specifications
    "requires", "ensures", "invariant",
    // statements
    "fold", "unfold", "inhale", "exhale", "new", "assert", "assume", "package", "apply",
    // control flow
    "while", "if", "elseif", "else", "goto", "label",
    // special fresh block
    "fresh", "constraining",
    // sequences
    "Seq",
    // sets and multisets
    "Set", "Multiset", "union", "intersection", "setminus", "subset",
    // prover hint expressions
    "unfolding", "in", "applying",
    // old expression
    "old", LHS_OLD_LABEL,
    // other expressions
    "let",
    // quantification
    "forall", "exists", "forperm",
    // permission syntax
    "acc", "wildcard", "write", "none", "epsilon", "perm",
    // modifiers
    "unique") | ParserExtension.extendedKeywords


  lazy val atom: P[PExp] = P(ParserExtension.newExpAtStart | integer | booltrue | boolfalse | nul | old
    | result | unExp
    | "(" ~ exp ~ ")" | accessPred | inhaleExhale | perm | let | quant | forperm | unfolding | applying
    | setTypedEmpty | explicitSetNonEmpty | multiSetTypedEmpty | explicitMultisetNonEmpty | seqTypedEmpty
    | seqLength | explicitSeqNonEmpty | seqRange | fapp | typedFapp | idnuse | ParserExtension.newExpAtEnd)


  lazy val result: P[PResultLit] = P(keyword("result").map { _ => PResultLit() })

  lazy val unExp: P[PUnExp] = P((CharIn("-!").! ~~ suffixExpr).map { case (a, b) => PUnExp(a, b) })

  lazy val strInteger: P[String] = P(CharIn('0' to '9').rep(1)).!

  lazy val integer: P[PIntLit] = strInteger.filter(s => !s.contains(' ')).map { s => PIntLit(BigInt(s)) }

  lazy val booltrue: P[PBoolLit] = P(keyword("true")).map(_ => PBoolLit(b = true))

  lazy val boolfalse: P[PBoolLit] = P(keyword("false")).map(_ => PBoolLit(b = false))

  lazy val nul: P[PNullLit] = P(keyword("null")).map(_ => PNullLit())

  lazy val identifier: P[Unit] = P(CharIn('A' to 'Z', 'a' to 'z', "$_") ~~ CharIn('0' to '9', 'A' to 'Z', 'a' to 'z', "$_").repX)

  lazy val ident: P[String] = P(identifier.!).filter(a => !keywords.contains(a)).opaque("invalid identifier (could be a keyword)")

  lazy val idnuse: P[PIdnUse] = P(ident).map(PIdnUse)

  lazy val oldLabel: P[PIdnUse] = P(idnuse | LHS_OLD_LABEL.!).map { case idnuse: PIdnUse => idnuse
  case LHS_OLD_LABEL => PIdnUse(LHS_OLD_LABEL)}

  lazy val old: P[PExp] = P(StringIn("old") ~ (parens(exp).map(POld) | ("[" ~ oldLabel ~ "]" ~ parens(exp)).map { case (a, b) => PLabelledOld(a, b) }))

  lazy val magicWandExp: P[PExp] = P(orExp ~ ("--*".! ~ exp).?).map { case (a, b) => b match {
    case Some(c) => PMagicWandExp(a, c._2)
    case None => a
  }}

  lazy val trueMagicWandExp: P[PExp] = P(trueOrExp ~ ("--*".! ~ trueExp).?).map { case (a, b) => b match {
    case Some(c) => PMagicWandExp(a, c._2)
    case None => a
  }}

  lazy val realMagicWandExp: P[PMagicWandExp] = P(orExp ~ "--*".! ~ exp).map { case (a,_,c) => PMagicWandExp(a,c)}

  lazy val implExp: P[PExp] = P(magicWandExp ~ (StringIn("==>").! ~ implExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }
  lazy val trueImplExp: P[PExp] = P(trueMagicWandExp ~ (StringIn("==>").! ~ trueImplExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val iffExp: P[PExp] = P(implExp ~ ("<==>".! ~ iffExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val trueIffExp: P[PExp] = P(trueImplExp ~ ("<==>".! ~ trueIffExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val iteExpr: P[PExp] = P(iffExp ~ ("?" ~ iteExpr ~ ":" ~ iteExpr).?).map { case (a, b) => b match {
    case Some(c) => PCondExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val trueIteExpr: P[PExp] = P(trueIffExp ~ ("?" ~ iteExpr ~ ":" ~ trueIteExpr).?).map { case (a, b) => b match {
    case Some(c) => PCondExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val exp: P[PExp] = P(iteExpr)

  lazy val trueExp: P[PExp] = P(trueIteExpr)

  lazy val suffix: fastparse.noApi.Parser[SuffixedExpressionGenerator[PExp]] =
    P(("." ~ idnuse).map { id => SuffixedExpressionGenerator[PExp]((e: PExp) => PFieldAccess(e, id)) } |
      ("[" ~ Pass ~ ".." ~/ exp ~ "]").map { n => SuffixedExpressionGenerator[PExp]((e: PExp) => PSeqTake(e, n)) } |
      ("[" ~ exp ~ ".." ~ Pass ~ "]").map { n => SuffixedExpressionGenerator[PExp]((e: PExp) => PSeqDrop(e, n)) } |
      ("[" ~ exp ~ ".." ~ exp ~ "]").map { case (n, m) => SuffixedExpressionGenerator[PExp]((e: PExp) => PSeqDrop(PSeqTake(e, m), n)) } |
      ("[" ~ exp ~ "]").map { e1 => SuffixedExpressionGenerator[PExp]((e0: PExp) => PSeqIndex(e0, e1)) } |
      ("[" ~ exp ~ ":=" ~ exp ~ "]").map { case (i, v) => SuffixedExpressionGenerator[PExp]((e: PExp) => PSeqUpdate(e, i, v)) })

  lazy val suffixExpr: P[PExp] = P((atom ~ suffix.rep).map { case (fac, ss) => foldPExp[PExp](fac, ss) })

  lazy val realSuffixExpr: P[PExp] = P((atom ~ suffix.rep).map { case (fac, ss) => foldPExp[PExp](fac, ss) })

  lazy val termOp: P[String] = P(StringIn("*", "/", "\\", "%").!)

  lazy val term: P[PExp] = P((suffixExpr ~ termd.rep).map { case (a, ss) => foldPExp[PExp](a, ss) })

  lazy val termd: P[SuffixedExpressionGenerator[PExp]] = P(termOp ~ suffixExpr).map { case (op, id) => SuffixedExpressionGenerator[PExp]((e: PExp) => PBinExp(e, op, id)) }

  lazy val sumOp: P[String] = P(StringIn("++", "+", "-").! | keyword("union").! | keyword("intersection").! | keyword("setminus").! | keyword("subset").!)

  lazy val sum: P[PExp] = P((term ~ sumd.rep).map { case (a, ss) => foldPExp[PBinExp](a, ss) })

  lazy val sumd: P[SuffixedExpressionGenerator[PBinExp]] = P(sumOp ~ term).map { case (op, id) => SuffixedExpressionGenerator[PBinExp]((e: PExp) => PBinExp(e, op, id)) }

  lazy val cmpOp = P(StringIn("<=", ">=", "<", ">").! | keyword("in").!)
  lazy val trueCmpOp = P(StringIn("<=", ">=", "<", ">").!)

  lazy val cmpExp: P[PExp] = P(sum ~ (cmpOp ~ cmpExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val trueCmpExp: P[PExp] = P(sum ~ (trueCmpOp ~ trueCmpExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val eqOp = P(StringIn("==", "!=").!)

  lazy val eqExp: P[PExp] = P(cmpExp ~ (eqOp ~ eqExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val trueEqExp: P[PExp] = P(trueCmpExp ~ (eqOp ~ trueEqExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val andExp: P[PExp] = P(eqExp ~ ("&&".! ~ andExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val trueAndExp: P[PExp] = P(trueEqExp ~ ("&&".! ~ trueAndExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val orExp: P[PExp] = P(andExp ~ ("||".! ~ orExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val trueOrExp: P[PExp] = P(trueAndExp ~ ("||".! ~ trueOrExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }}

  lazy val accessPredImpl: P[PAccPred] = P((keyword("acc") ~/ "(" ~ locAcc ~ ("," ~ exp).? ~ ")").map {
    case (loc, perms) => PAccPred(loc, perms.getOrElse(PFullPerm()))
  })

  lazy val accessPred: P[PAccPred] = P(accessPredImpl.map(acc => {
    val perm = acc.perm
    if (FastPositions.getStart(perm) == NoPosition) {
      FastPositions.setStart(perm, acc.start)
      FastPositions.setFinish(perm, acc.finish)
    }
    acc
  }))

  lazy val resAcc: P[PResourceAccess] = P(locAcc | realMagicWandExp)

  lazy val locAcc: P[PLocationAccess] = P(fieldAcc | predAcc)

  lazy val fieldAcc: P[PFieldAccess] =
    P(NoCut(realSuffixExpr.filter(isFieldAccess)).map {
      case fa: PFieldAccess => fa
      case other => sys.error(s"Unexpectedly found $other")
    })

  lazy val predAcc: P[PLocationAccess] = P(fapp)

  lazy val actualArgList: P[Seq[PExp]] = P(exp.rep(sep = ","))

  lazy val inhaleExhale: P[PExp] = P("[" ~ exp ~ "," ~ exp ~ "]").map { case (a, b) => PInhaleExhaleExp(a, b) }

  lazy val perm: P[PExp] =
    P(keyword("none").map(_ => PNoPerm()) | keyword("wildcard").map(_ => PWildcard()) | keyword("write").map(_ => PFullPerm())
    | keyword("epsilon").map(_ => PEpsilon()) | ("perm" ~ parens(resAcc)).map(PCurPerm))

  lazy val let: P[PExp] = P(
    ("let" ~/ idndef ~ "==" ~  trueExp  ~ "in" ~ exp).map { case (id, exp1, exp2) =>
      /* Type unresolvedType is expected to be replaced with the type of exp1
       * after the latter has been resolved
       * */
      val unresolvedType = PUnknown().setPos(id)
      val formalArgDecl = PFormalArgDecl(id, unresolvedType).setPos(id)
      val nestedScope = PLetNestedScope(formalArgDecl, exp2).setPos(exp2)

      PLet(exp1, nestedScope)
    })

  lazy val idndef: P[PIdnDef] = P(ident).map(PIdnDef)

  lazy val quant: P[PExp] = P((keyword("forall") ~/ nonEmptyFormalArgList ~ "::" ~/ trigger.rep ~ exp).map { case (a, b, c) => PForall(a, b, c) } |
    (keyword("exists") ~/ nonEmptyFormalArgList ~ "::" ~ trigger.rep ~ exp).map { case (a, b, c) => PExists(a, b, c) })

  lazy val nonEmptyFormalArgList: P[Seq[PFormalArgDecl]] = P(formalArg.rep(min = 1, sep = ","))

  lazy val formalArg: P[PFormalArgDecl] = P(idndef ~ ":" ~ typ).map { case (a, b) => PFormalArgDecl(a, b) }

  lazy val typ: P[PType] = P(primitiveTyp | domainTyp | seqType | setType | multisetType)

  lazy val domainTyp: P[PDomainType] = P((idnuse ~ "[" ~ typ.rep(sep = ",") ~ "]").map { case (a, b) => PDomainType(a, b) } |
    // domain type without type arguments (might also be a type variable)
    idnuse.map(name => PDomainType(name, Nil)))

  lazy val seqType: P[PType] = P(keyword("Seq") ~/ "[" ~ typ ~ "]").map(PSeqType)

  lazy val setType: P[PType] = P(keyword("Set") ~/ "[" ~ typ ~ "]").map(PSetType)

  lazy val multisetType: P[PType] = P(keyword("Multiset") ~/ "[" ~ typ ~ "]").map(PMultisetType)

  lazy val primitiveTyp: P[PType] = P(keyword("Rational").map(_ => PPrimitiv("Perm"))
    | (StringIn("Int", "Bool", "Perm", "Ref") ~~ !identContinues).!.map(PPrimitiv))

  lazy val trigger: P[PTrigger] = P("{" ~/ exp.rep(sep = ",") ~ "}").map(s => PTrigger(s))

  lazy val forperm: P[PExp] = P(keyword("forperm") ~ nonEmptyFormalArgList ~ "[" ~ resAcc ~ "]" ~ "::" ~/ exp).map {
    case (args, res, body) => PForPerm(args, res, body)
  }

  lazy val unfolding: P[PExp] = P(keyword("unfolding") ~/ predicateAccessPred ~ "in" ~ exp).map { case (a, b) => PUnfolding(a, b) }

  lazy val predicateAccessPred: P[PAccPred] = P(accessPred | predAcc.map (
    loc => {
      val perm = PFullPerm()
      FastPositions.setStart(perm, loc.start)
      FastPositions.setFinish(perm, loc.finish)
      PAccPred(loc, perm)
  }))

  lazy val setTypedEmpty: P[PExp] = collectionTypedEmpty("Set", PEmptySet)

  lazy val explicitSetNonEmpty: P[PExp] = P("Set" ~ "(" ~/ exp.rep(sep = ",", min = 1) ~ ")").map(PExplicitSet)

  lazy val explicitMultisetNonEmpty: P[PExp] = P("Multiset" ~ "(" ~/ exp.rep(min = 1, sep = ",") ~ ")").map(PExplicitMultiset)

  lazy val multiSetTypedEmpty: P[PExp] = collectionTypedEmpty("Multiset", PEmptyMultiset)

  lazy val seqTypedEmpty: P[PExp] = collectionTypedEmpty("Seq", PEmptySeq)

  lazy val seqLength: P[PExp] = P("|" ~ exp ~ "|").map(PSize)

  lazy val explicitSeqNonEmpty: P[PExp] = P("Seq" ~ "(" ~/ exp.rep(min = 1, sep = ",") ~ ")").map(PExplicitSeq)

  private def collectionTypedEmpty(name: String, typeConstructor: PType => PExp): P[PExp] =
    P(`name` ~ ("[" ~/ typ ~ "]").? ~ "(" ~ ")").map(typ => typeConstructor(typ.getOrElse(PTypeVar("#E"))))


  lazy val seqRange: P[PExp] = P("[" ~ exp ~ ".." ~ exp ~ ")").map { case (a, b) => PRangeSeq(a, b) }


  lazy val fapp: P[PCall] = P(idnuse ~ parens(actualArgList)).map {
    case (func, args) => PCall(func, args, None)
  }

  lazy val typedFapp: P[PExp] = P(parens(idnuse ~ parens(actualArgList) ~ ":" ~ typ)).map {
    case (func, args, typeGiven) => PCall(func, args, Some(typeGiven))
  }

  lazy val stmt: P[PStmt] = P(ParserExtension.newStmtAtStart | macroassign | fieldassign | localassign | fold | unfold | exhale | assertP |
    inhale | assume | ifthnels | whle | varDecl | defineDecl | newstmt | fresh | constrainingBlock |
    methodCall | goto | lbl | packageWand | applyWand | macroref | block | ParserExtension.newStmtAtEnd)

  lazy val nodefinestmt: P[PStmt] = P(ParserExtension.newStmtAtStart | fieldassign | localassign | fold | unfold | exhale | assertP |
    inhale | assume | ifthnels | whle | varDecl | newstmt | fresh | constrainingBlock |
    methodCall | goto | lbl | packageWand | applyWand | macroref | block | ParserExtension.newStmtAtEnd)

  lazy val macroref: P[PMacroRef] = P(idnuse).map(a => PMacroRef(a))

  lazy val fieldassign: P[PFieldAssign] = P(fieldAcc ~ ":=" ~ exp).map { case (a, b) => PFieldAssign(a, b) }

  lazy val macroassign: P[PMacroAssign] = P(NoCut(fapp) ~ ":=" ~ exp).map { case (call, exp) => PMacroAssign(call, exp) }

  lazy val localassign: P[PVarAssign] = P(idnuse ~ ":=" ~ exp).map { case (a, b) => PVarAssign(a, b) }

  lazy val fold: P[PFold] = P("fold" ~ predicateAccessPred).map(PFold)

  lazy val unfold: P[PUnfold] = P("unfold" ~ predicateAccessPred).map(PUnfold)

  lazy val exhale: P[PExhale] = P(keyword("exhale") ~/ exp).map(PExhale)

  lazy val assertP: P[PAssert] = P(keyword("assert") ~/ exp).map(PAssert)

  lazy val inhale: P[PInhale] = P(keyword("inhale") ~/ exp).map(PInhale)

  lazy val assume: P[PAssume] = P(keyword("assume") ~/ exp).map(PAssume)

  lazy val ifthnels: P[PIf] = P("if" ~ "(" ~ exp ~ ")" ~ block ~ elsifEls).map {
    case (cond, thn, ele) => PIf(cond, thn, ele)
  }

  /**
    * This parser is wrapped in another parser because otherwise the position
    * in rules like [[block.?]] are not set properly.
    */
  lazy val block: P[PSeqn] = P(P("{" ~/ stmts ~ "}").map(PSeqn))

  lazy val stmts: P[Seq[PStmt]] = P(stmt ~/ ";".?).rep

  lazy val elsifEls: P[PSeqn] = P(elsif | els)

  lazy val elsif: P[PSeqn] = P("elseif" ~/ "(" ~ exp ~ ")" ~ block ~ elsifEls).map {
    case (cond, thn, ele) => PSeqn(Seq(PIf(cond, thn, ele)))
  }

  lazy val els: P[PSeqn] = (keyword("else") ~/ block).?.map { block => block.getOrElse(PSeqn(Nil)) }

  lazy val whle: P[PWhile] = P(keyword("while") ~/ "(" ~ exp ~ ")" ~ inv.rep ~ block).map {
    case (cond, invs, body) => PWhile(cond, invs, body)
  }

  lazy val inv: P[PExp] = P((keyword("invariant") ~ exp ~ ";".?) | ParserExtension.invSpecification)

  lazy val varDecl: P[PLocalVarDecl] = P(keyword("var") ~/ idndef ~ ":" ~ typ ~ (":=" ~ exp).?).map { case (a, b, c) => PLocalVarDecl(a, b, c) }

  lazy val defineDecl: P[PDefine] = P(keyword("define") ~/ idndef ~ ("(" ~ idndef.rep(sep = ",") ~ ")").? ~ (exp | "{" ~ (nodefinestmt ~ ";".?).rep ~ "}")).map {
    case (a, b, c) => c match {
      case e: PExp => PDefine(a, b, e)
      case ss: Seq[PStmt]@unchecked => PDefine(a, b, PSeqn(ss))
    }
  }

  lazy val newstmt: P[PNewStmt] = starredNewstmt | regularNewstmt

  lazy val regularNewstmt: P[PRegularNewStmt] = P(idnuse ~ ":=" ~ "new" ~ "(" ~ idnuse.rep(sep = ",") ~ ")").map { case (a, b) => PRegularNewStmt(a, b) }

  lazy val starredNewstmt: P[PStarredNewStmt] = P(idnuse ~ ":=" ~ "new" ~ "(" ~ "*" ~ ")").map(PStarredNewStmt)

  lazy val fresh: P[PFresh] = P(keyword("fresh") ~ idnuse.rep(sep = ",")).map(vars => PFresh(vars))

  lazy val constrainingBlock: P[PConstraining] = P("constraining" ~ "(" ~ idnuse.rep(sep = ",") ~ ")" ~ block).map { case (vars, s) => PConstraining(vars, s) }

  lazy val methodCall: P[PMethodCall] = P((idnuse.rep(sep = ",") ~ ":=").? ~ idnuse ~ parens(exp.rep(sep = ","))).map {
    case (None, method, args) => PMethodCall(Nil, method, args)
    case (Some(targets), method, args) => PMethodCall(targets, method, args)
  }

  lazy val goto: P[PGoto] = P("goto" ~/ idnuse).map(PGoto)

  lazy val lbl: P[PLabel] = P(keyword("label") ~/ idndef ~ (keyword("invariant") ~/ exp).rep).map { case (name, invs) => PLabel(name, invs) }

  lazy val packageWand: P[PPackageWand] = P("package" ~/ magicWandExp ~ block.?).map {
    case (wand, Some(proofScript)) => PPackageWand(wand, proofScript)
    case (wand, None) => PPackageWand(wand, PSeqn(Seq()))
  }

  lazy val applyWand: P[PApplyWand] = P("apply" ~/ magicWandExp).map(PApplyWand)

  lazy val applying: P[PExp] = P(keyword("applying") ~/ "(" ~ magicWandExp  ~ ")" ~ "in" ~ exp).map { case (a, b) => PApplying(a, b) }

  lazy val programDecl: P[PProgram] = P((ParserExtension.newDeclAtStart | preambleImport | defineDecl | domainDecl | fieldDecl | functionDecl | predicateDecl | methodDecl | ParserExtension.newDeclAtEnd).rep).map {
    decls => {
      PProgram(
        decls.collect { case i: PImport => i }, // Imports
        decls.collect { case d: PDefine => d }, // Macros
        decls.collect { case d: PDomain => d }, // Domains
        decls.collect { case f: PField => f }, // Fields
        decls.collect { case f: PFunction => f }, // Functions
        decls.collect { case p: PPredicate => p }, // Predicates
        decls.collect { case m: PMethod => m }, // Methods
        decls.collect { case e: PExtender => e }, // Extensions
        Seq() // Parse Errors
      )
    }
  }

  lazy val preambleImport: P[PImport] = P(keyword("import") ~/ (
      quoted(relativeFilePath.!).map(filename => PLocalImport(filename)) |
      angles(relativeFilePath.!).map(filename => PStandardImport(filename))
    )
  )

  lazy val relativeFilePath: P[String] = P(CharIn("~.").?.! ~~ (CharIn("/").? ~~ CharIn(".", 'A' to 'Z', 'a' to 'z', '0' to '9', "_- \n\t")).rep(1))

  lazy val domainDecl: P[PDomain] = P("domain" ~/ idndef ~ ("[" ~ domainTypeVarDecl.rep(sep = ",") ~ "]").? ~ "{" ~ (domainFunctionDecl | axiomDecl).rep ~
    "}").map {
    case (name, typparams, members) =>
      val funcs = members collect { case m: PDomainFunction1 => m }
      val axioms = members collect { case m: PAxiom1 => m }
      PDomain(
        name,
        typparams.getOrElse(Nil),
        funcs map (f => PDomainFunction(f.idndef, f.formalArgs, f.typ, f.unique)(PIdnUse(name.name)).setPos(f)),
        axioms map (a => PAxiom(a.idndef, a.exp)(PIdnUse(name.name)).setPos(a)))
  }

  lazy val domainTypeVarDecl: P[PTypeVarDecl] = P(idndef).map(PTypeVarDecl)

  lazy val domainFunctionDecl: P[PDomainFunction1] = P("unique".!.? ~ functionSignature ~ ";".?).map {
    case (unique, fdecl) => fdecl match {
      case (name, formalArgs, t) => PDomainFunction1(name, formalArgs, t, unique.isDefined)
    }
  }

  lazy val functionSignature = P("function" ~ idndef ~ "(" ~ formalArgList ~ ")" ~ ":" ~ typ)

  lazy val formalArgList: P[Seq[PFormalArgDecl]] = P(formalArg.rep(sep = ","))

  lazy val axiomDecl: P[PAxiom1] = P(keyword("axiom") ~ idndef ~ "{" ~ exp ~ "}" ~ ";".?).map { case (a, b) => PAxiom1(a, b) }

  lazy val fieldDecl: P[PField] = P("field" ~/ idndef ~ ":" ~ typ ~ ";".?).map { case (a, b) => PField(a, b) }

  lazy val functionDecl: P[PFunction] = P("function" ~/ idndef ~ "(" ~ formalArgList ~ ")" ~ ":" ~ typ ~ pre.rep ~
    post.rep ~ ("{" ~ exp ~ "}").?).map { case (a, b, c, d, e, f) => PFunction(a, b, c, d, e, f) }


  lazy val pre: P[PExp] = P(("requires" ~/ exp ~ ";".?) | ParserExtension.preSpecification)

  lazy val post: P[PExp] = P(("ensures" ~/ exp ~ ";".?) | ParserExtension.postSpecification)

  lazy val decCl: P[Seq[PExp]] = P(exp.rep(sep = ","))

  lazy val predicateDecl: P[PPredicate] = P("predicate" ~/ idndef ~ "(" ~ formalArgList ~ ")" ~ ("{" ~ exp ~ "}").?).map { case (a, b, c) => PPredicate(a, b, c) }

  lazy val methodDecl: P[PMethod] = P(methodSignature ~/ pre.rep ~ post.rep ~ block.?).map {
    case (name, args, rets, pres, posts, body) =>
      PMethod(name, args, rets.getOrElse(Nil), pres, posts, body)
  }

  lazy val methodSignature = P("method" ~/ idndef ~ "(" ~ formalArgList ~ ")" ~ ("returns" ~ "(" ~ formalArgList ~ ")").?)

  lazy val fastparser: P[PProgram] = P(Start ~ programDecl ~ End)


}
