/*
*************************************************************************************
* Copyright 2014 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.services.policies

import scala.util.parsing.combinator.RegexParsers
import net.liftweb.common.{Failure => FailedBox, _}
import com.normation.rudder.domain.parameters.ParameterName
import net.liftweb.json.JsonAST.JValue
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.policies.PolicyModeOverrides

/**
 * A parser that handle parameterized value of
 * directive variables.
 *
 * The parameterization is to be taken in the context of
 * a rule (i.e, a directive applied to
 * a target), and in the scope of one node of the target
 * (as if you were processing one node at a time).
 *
 * The general parameterized value are of the form:
 * ${rudder.xxx}
 * were "xxx" is the parameter to lookup.
 *
 * We handle 3 kinds of parameterizations:
 * 1/ ${rudder.param.XXX}
 *    where:
 *    - XXX is a parameter configured in Rudder
 *      (for now global, but support for node-contextualised is implemented)
 *    - XXX is case sensisite
 *    - XXX's value can contains other interpolation
 *
 * 2/ ${rudder.node.ACCESSOR}
 *    where:
 *    - "node" is a keyword ;
 *    - ACCESSOR is an accessor for that node, explained below.
 *    - the value can not contains other interpolation
 *
 * 3/ ${node.properties[keyone][keytwo]} or
 *   ${node.properties[keyone][keytwo] | node } or
 *    ${node.properties[keyone][keytwo] | default = XXXX }
 *
 *    where:
 *    - keyone, keytwo are path on json values
 *    - the return value is the string representation, in compact mode, of the resulting access
 *    - if the key is not found, we raise an error
 *    - spaces are authorized around separators ([,],|,}..)
 *    - options can be given by adding "| option", with available option:
 *      - "node" : mean that the interpolation will be done on the node,
 *        and so the parameter must be outputed as an equivalent string
 *        parameter for the node (the same without "| node")
 *      - default = XXX mean that if the properties is not found, "XXX" must
 *        be used in place, with XXX one of:
 *        - a double-quoted string, like: "default value",
 *        - a triple-double quoted string, like: """ some "default" must be used""",
 *        - an other parameter among the 3 described here.
 *        Quoted string may contain parameters, like in:
 *        ${node.properties[charge] | default = """Node "color" is: ${node.properties[color] | "blue" }""" }
 *
 * Accessor are keywords which allows to reach value in a context, exactly like
 * properties in object oriented programming.
 *
 * Accessors for parameters
 *    ${rudder.param.ACCESSOR} : replace by the value for the parameter with the name ACCESSOR
 *
 * Accessors for node
 * ------------------
 *   ${rudder.node.id} : internal ID of the node (generally an UUID)
 *   ${rudder.node.hostname} : hostname of the node
 *   ${rudder.node.admin} : login (or username) of the node administrator, or root, or at least
 *                   the login to use to run the agent
 *   ${rudder.node.policyserver.ACCESSOR} : information about the policyserver of the node.
 *                                    ACCESSORs are the same than for ${rudder.node}
 *
 *  We do have all the logistic to give access to any given inventory parameter, but for now,
 *  we still need to decide:
 *  - how we are managing it in a generic way, i.e given any addition to the inventory, having
 *    access to it without modifying that code (xpath like access to information from parameter
 *    structure)
 *  - what are the consequences in the reporting & expected reports, in particular what happen
 *    to a parameter whose value is a list (iteration, list => string, etc)
 */

trait InterpolatedValueCompiler {

  /**
   *
   * Parse a value looking for interpolation variable in it.
   *
   * Return a Box, where Full denotes a successful
   * parsing of all values, and EmptyBox. an error.
   */
  def compile(value: String): Box[InterpolationContext => Box[String]]

  /**
   *
   * Parse a value to translate token to a valid value for the agent passed as parameter.
   *
   * Return a Box, where Full denotes a successful
   * parsing of all values, and EmptyBox. an error.
   */
  def translateToAgent (value : String, agentType : AgentType) : Box[String]

}

object InterpolatedValueCompilerImpl {

  /*
   * Our AST for interpolated variable:
   * A string to look for interpolation is a list of token.
   * A token can be a plain string with no variable, or something
   * to interpolate. For now, we can interpolate two kind of variables:
   * - node information (thanks to a pointed path to the interesting property)
   * - rudder parameters (only globals for now)
   */
  sealed trait Token //could be Either[CharSeq, Interpolation]

  case class   CharSeq(s:String) extends Token
  sealed trait Interpolation     extends Token

  //everything is expected to be lower case
  final case class NodeAccessor(path:List[String]) extends Interpolation
  //everything is expected to be lower case
  final case class Param(name:String)              extends Interpolation
  //here, we keep the case as it is given
  final case class Property(path: List[String], opt: Option[PropertyOption])    extends Interpolation

  //here, we have node property option
  sealed trait PropertyOption
  final case object InterpreteOnNode                 extends PropertyOption
  final case class  DefaultValue(value: List[Token]) extends PropertyOption
}

class InterpolatedValueCompilerImpl extends RegexParsers with InterpolatedValueCompiler {

  import InterpolatedValueCompilerImpl._

  /*
   * Number of time we allows to recurse for interpolated variable
   * evaluated to other interpolated variables.
   *
   * It allows to detect cycle by brut force, but may raise false
   * positive too:
   *
   * Ex: two variable calling the other one:
   * a => b => a => b => a STOP
   *
   * Ex: a false positive:
   *
   * a => b => c => d => e => f => ... => "42"
   *
   * This is not a very big limitation, as we are not building
   * a programming language, and users can easily resolve
   * the problem by just making smaller cycle.
   *
   */
  val maxEvaluationDepth = 5

  /*
   * In our parser, whitespace are relevant,
   * we are not parsing a language here.
   */
  override val skipWhitespace = false

  /*
   * just call the parser on a value, and in case of successful parsing, interprete
   * the resulting AST (seq of token)
   */
  def compile(value: String): Box[InterpolationContext => Box[String]] = {
    parseAll(all, value) match {
      case NoSuccess(msg, remaining)  => FailedBox(s"""Error when parsing value "${value}", error message is: ${msg}""")
      case Success(tokens, remaining) => Full(parseToken(tokens))
    }
  }

  def translateToAgent(value: String, agent : AgentType): Box[String] = {
    parseAll(all, value) match {
      case NoSuccess(msg, remaining)  => FailedBox(s"""Error when parsing value "${value}", error message is: ${msg}""")
      case Success(tokens, remaining) => Full(tokens.map(translate(agent, _) ).mkString(""))
    }
  }

  /*
   * The funny part that for each token add the interpretation of the token
   * by composing interpretation function.
   */
  def parseToken(tokens:List[Token]): InterpolationContext => Box[String] = {
    def build(context: InterpolationContext) = {
      val init: Box[String] = Full("")
      ( init /: tokens){
        case (eb:EmptyBox, _ ) => eb
        case (Full(str), token) => analyse(context, token) match {
          case eb:EmptyBox => eb
          case Full(s) => Full(str + s)
        }
      }
    }

    build _
  }

  /*
   * The three following methods analyse token one by one and
   * given the token, build the function to execute to get
   * the final string (that may not succeed at run time, because of
   * unknown parameter, etc)
   */
  def analyse(context: InterpolationContext, token:Token): Box[String] = {
    token match {
      case CharSeq(s)          => Full(s)
      case NodeAccessor(path)  => getNodeAccessorTarget(context, path)
      case Param(name)         => getRudderGlobalParam(context, ParameterName(name))
      case Property(path, opt) => opt match {
        case None =>
          getNodeProperty(context, path)
        case Some(InterpreteOnNode) =>
          //in that case, we want to exactly output the agent-compatible string. For now, easy, only one string
          Full("${node.properties[" + path.mkString("][") + "]}")
        case Some(DefaultValue(optionTokens)) =>
          //in that case, we want to find the default value.
          //we authorize to have default value = ${node.properties[bla][bla][bla]|node},
          //because we may want to use prop1 and if node set, prop2 at run time.
          for {
            default <- parseToken(optionTokens)(context)
          } yield {
            getNodeProperty(context, path).openOr(default)
          }
      }
    }
  }

  // Transform a token to its correct value for the agent passed as parameter
  def translate(agent: AgentType, token:Token): String = {
    token match {
      case CharSeq(s)          => s
      case NodeAccessor(path)  => s"$${rudder.node.${path.mkString(".")}}"
      case Param(name)         => s"$${rudder.param.${name}}"
      case Property(path, opt) => agent match {
        case AgentType.Dsc =>
          s"$$($$node.properties[${path.mkString("][")}])"
        case AgentType.CfeCommunity | AgentType.CfeEnterprise =>
          s"$${node.properties[${path.mkString("][")}]}"
      }
    }
  }

  /**
   * Retrieve the global parameter from the node context.
   */
  def getRudderGlobalParam(context: InterpolationContext, paramName: ParameterName): Box[String] = {
    context.parameters.get(paramName) match {
      case Some(value) =>
        if(context.depth >= maxEvaluationDepth) {
          FailedBox(s"""Can not evaluted global parameter "${paramName.value}" because it uses an interpolation variable that depends upon """
           + s"""other interpolated variables in a stack more than ${maxEvaluationDepth} in depth. We fear it's a circular dependancy.""")
        } else value(context.copy(depth = context.depth+1))
      case _ => FailedBox(s"Error when trying to interpolate a variable: Rudder parameter not found: '${paramName.value}'")
    }
  }

  /**
   * Get the targeted accessed node information, checking that it exists.
   */
  def getNodeAccessorTarget(context: InterpolationContext, path: List[String]): Box[String] = {
    val error = FailedBox(s"Unknow interpolated variable $${node.${path.mkString(".")}}" )
    path match {
      case Nil => FailedBox("In node interpolated variable, at least one accessor must be provided")
      case access :: tail => access.toLowerCase :: tail match {
        case "id" :: Nil => Full(context.nodeInfo.id.value)
        case "hostname" :: Nil => Full(context.nodeInfo.hostname)
        case "admin" :: Nil => Full(context.nodeInfo.localAdministratorAccountName)
        case "state" :: Nil => Full(context.nodeInfo.state.name)
        case "policymode" :: Nil =>
          val effectivePolicyMode = context.globalPolicyMode.overridable match {
            case PolicyModeOverrides.Unoverridable =>
              context.globalPolicyMode.mode.name
            case PolicyModeOverrides.Always =>
              context.nodeInfo.policyMode.getOrElse(context.globalPolicyMode.mode).name
          }
          Full(effectivePolicyMode)
        case "policyserver" :: tail2 => tail2 match {
          case "id" :: Nil => Full(context.policyServerInfo.id.value)
          case "hostname" :: Nil => Full(context.policyServerInfo.hostname)
          case "admin" :: Nil => Full(context.policyServerInfo.localAdministratorAccountName)
          case _ => error
        }
        case seq => error
      }
    }
  }

  /**
   * Get the node property value, or fails if it does not exists.
   * If the path length is 1, only check that the property exists and
   * returned the corresponding string value.
   * If the path length is more than one, try to parse the string has a
   * json value and access the remaining part as a json path.
   */
  def getNodeProperty(context: InterpolationContext, path: List[String]): Box[String] = {
    val errmsg = s"Missing property '$${node.properties[${path.mkString("][")}]}' on node '${context.nodeInfo.hostname}' [${context.nodeInfo.id.value}]"
    path match {
      //we should not reach that case since we enforce at leat one match of [...] in the parser
      case Nil       => FailedBox(s"The syntax $${node.properties} is invalid, only $${node.properties[propertyname]} is accepted")
      case h :: tail => context.nodeInfo.properties.find(p => p.name == h) match {
        case None       => FailedBox(errmsg)
        case Some(prop) => tail match {
          case Nil     => Full(prop.renderValue)
          //here, we need to parse the value in json and try to find the asked path
          case subpath => getJsonProperty(subpath, prop.value) ?~! errmsg
        }
      }
    }
  }

  def getJsonProperty(path: List[String], json: JValue): Box[String] = {
    import net.liftweb.json._

    def access(json: => JValue, path: List[String]): JValue = path match {
      case Nil       => json
      case h :: tail => access( json \ h, tail)
    }

    for {
      prop <- access(json, path) match {
                case JNothing   => FailedBox(s"Can not find property in JSON '${compactRender(json)}'")
                case JString(s) => Full(s) //needed to special case to not have '\"' everywhere
                case x          => Full(compactRender(x))
              }
    } yield {
      prop
    }
  }

  //// parsing language

  //make a case insensitive regex from the parameter
  //modifier: see http://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html
  //i: ignore case
  //u: considere unicode char for ignore case
  //s: match end of line in ".*" pattern
  //m: multi-lines mode
  //exactly quote s - no regex authorized in
  def id(s: String) = ("""(?iu)\Q""" + s + """\E""").r

  def all       : Parser[List[Token]] = (rep1(interpol   | plainString ) | emptyVar)

  def space = """\s*""".r

  //empty string is a special case that must be look appart from plain string.
  //also parse full blank string, because no need to look for more case for them.
  def emptyVar    : Parser[List[CharSeq]] = """(?iums)(\s)*""".r ^^ { x => List(CharSeq(x)) }

  // plain string must not match our identifier, ${rudder.* and ${node.properties.*}
  // here we defined a function to build them, with the possibility to
  def plainString : Parser[CharSeq] = ("""(?iums)((?!(\Q${\E\s*rudder\s*\.|\Q${\E\s*node\s*\.\s*properties)).)+""").r  ^^ { CharSeq(_) }

  //identifier for step in the path or param names
  def propId      : Parser[String] = """[\-_a-zA-Z0-9]+""".r

  // all interpolation
  def interpol    : Parser[Interpolation] = rudderProp | nodeProp

  //an interpolated variable looks like: ${rudder.XXX}, or ${RuDder.xXx}
  def rudderProp  : Parser[Interpolation] = "${" ~> space ~> id("rudder") ~> space ~> id(".") ~> space ~>(nodeAccess | parameter ) <~ space <~ "}"

  //a node path looks like: ${rudder.node.HERE.PATH}
  def nodeAccess  : Parser[Interpolation] = { id("node") ~> space ~> id(".") ~> space ~> repsep(propId, space ~> id(".") ~> space) ^^ { seq => NodeAccessor(seq) } }

  //a parameter looks like: ${rudder.PARAM_NAME}
  def parameter   : Parser[Interpolation] = { id("param") ~> space ~> id(".") ~> space ~> propId ^^ { p => Param(p) } }

  //an interpolated variable looks like: ${rudder.XXX}, or ${RuDder.xXx}
  def nodeProp    : Parser[Interpolation] = "${" ~> space ~> id("node") ~> space ~> id(".") ~> space ~> id("properties") ~> rep1( space ~> "[" ~> space ~>
                                            propId <~ space <~ "]" ) ~ opt(propOption) <~ space <~ "}" ^^ { case ~(path, opt) => Property(path, opt) }

  //here, the number of " must be strictly decreasing - ie. triple quote before
  def propOption  : Parser[PropertyOption] = space ~> "|" ~> space ~>  ( onNodeOpt |  "default" ~> space ~> "=" ~> space ~>
                                             ( interpolOpt | emptyTQuote | tqString | emptySQuote | sqString )  )

  def onNodeOpt   : Parser[InterpreteOnNode.type] = id("node") ^^^ InterpreteOnNode

  def interpolOpt : Parser[DefaultValue] = interpol ^^ { x => DefaultValue(x::Nil) }
  def emptySQuote : Parser[DefaultValue] = id("\"\"")         ^^   { _ => DefaultValue(CharSeq("")::Nil) }
  def emptyTQuote : Parser[DefaultValue] = id("\"\"\"\"\"\"") ^^   { _ => DefaultValue(CharSeq("")::Nil) }

  //string must be simple or triple quoted string
  def sqString    : Parser[DefaultValue] = id("\"")  ~> rep1(sqplainStr | interpol )<~ id("\"") ^^ { case x => DefaultValue(x) }
  def sqplainStr  : Parser[Token]        = ("""(?iums)((?!(\Q${\E\s*rudder\s*\.|\Q${\E\s*node\s*\.\s*properties|")).)+""").r  ^^ { str => CharSeq(str) }

  def tqString    : Parser[DefaultValue]   = id("\"\"\"")  ~> rep1(tqplainStr | interpol )<~ id("\"\"\"") ^^ { case x => DefaultValue(x) }
  def tqplainStr  : Parser[Token]          = ("""(?iums)((?!(\Q${\E\s*rudder\s*\.|\Q${\E\s*node\s*\.\s*properties|"""+"\"\"\""+ """)).)+""").r  ^^ { str => CharSeq(str) }

}
