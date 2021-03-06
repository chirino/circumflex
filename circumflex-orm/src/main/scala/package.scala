package ru.circumflex

import ru.circumflex.core._
import orm._
import java.util.regex.Pattern
import net.sf.ehcache.CacheManager
import java.sql.ResultSet

/*!# The `orm` Package

Package `orm` contains different shortcuts, utilities, helpers and implicits --
the basis of DSL of Circumflex ORM.

You should import this package to use Circumflex ORM in your application:

    import ru.circumflex.orm._
*/
package object orm {

  val ORM_LOG = new Logger("ru.circumflex.orm")

  // Commons

  val connectionProvider: ConnectionProvider = cx.instantiate[ConnectionProvider](
    "orm.connectionProvider", new DefaultConnectionProvider)

  val typeConverter: TypeConverter = cx.instantiate[TypeConverter](
    "orm.typeConverter", new TypeConverter)

  val dialect: Dialect = cx.instantiate[Dialect]("orm.dialect", cx.get("orm.connection.url") match {
    case Some(url: String) =>
      if (url.startsWith("jdbc:postgresql:")) new PostgreSQLDialect
      else if (url.startsWith("jdbc:mysql:")) new MySQLDialect
      else if (url.startsWith("jdbc:oracle:")) new OracleDialect
      else if (url.startsWith("jdbc:h2:")) new H2Dialect
      else new Dialect
    case _ => new Dialect
  })

  val transactionManager: TransactionManager = cx.instantiate[TransactionManager](
    "orm.transactionManager", new DefaultTransactionManager)

  val defaultSchema: Schema = new Schema(
    cx.get("orm.defaultSchema").map(_.toString).getOrElse("public"))

  val ehcacheManager: CacheManager = cx.get("orm.ehcache.config") match {
    case Some(f: String) => new CacheManager(f)
    case _ => new CacheManager()
  }

  def contextCache = CacheService.get

  def tx: Transaction = transactionManager.get
  def COMMIT() = tx.commit()
  def ROLLBACK() = tx.rollback()

  // Implicits

  // for nodes, fields and records

  implicit def association2field[K, C <: Record[_, C], P <: Record[K, P]](
      association: Association[K, C, P]): Field[K, C] = association.field
  implicit def relation2node[PK, R <: Record[PK, R]](relation: Relation[PK, R]): RelationNode[PK, R] =
    new RelationNode[PK, R](relation)
  implicit def node2relation[PK, R <: Record[PK, R]](node: RelationNode[PK, R]): R = {
    ctx("orm.lastAlias") = node.alias
    node.relation.asInstanceOf[R]
  }
  implicit def field2str(field: Field[_, _]): String = ctx.get("orm.lastAlias") match {
    case Some(alias: String) =>
      ctx.remove("orm.lastAlias")
      alias + "." + field.name
    case _ => field.name
  }

  // for predicates

  implicit def string2helper(expression: String): SimpleExpressionHelper =
    new SimpleExpressionHelper(expression)
  implicit def field2helper(field: Field[_, _]): SimpleExpressionHelper =
    new SimpleExpressionHelper(field2str(field))
  implicit def string2predicate(expression: String): Predicate =
    new SimpleExpression(expression, Nil)
  implicit def paramExpr2predicate(expression: ParameterizedExpression): Predicate =
    new SimpleExpression(expression.toSql, expression.parameters)
  implicit def predicate2aggregateHelper(predicate: Predicate) =
    new AggregatePredicateHelper(predicate)
  implicit def predicate2string(predicate: Predicate): String = predicate.toInlineSql

  // for orders

  implicit def string2order(expression: String): Order =
    new Order(expression, Nil)
  implicit def field2order(field: Field[_, _]): Order =
    new Order(field2str(field), Nil)

  // for projections

  implicit def string2projection(expression: String): Projection[Any] =
    new ExpressionProjection[Any](expression)
  implicit def field2projection[T](field: Field[T, _]): Projection[T] =
    new ExpressionProjection[T](field2str(field))

  implicit def pair2proj[T1, T2](
      t: (Projection[T1], Projection[T2])) = new PairProjection(t._1, t._2)

  // Constants

  val NO_ACTION = ForeignKeyAction(dialect.fkNoAction)
  val CASCADE = ForeignKeyAction(dialect.fkCascade)
  val RESTRICT = ForeignKeyAction(dialect.fkRestrict)
  val SET_NULL = ForeignKeyAction(dialect.fkSetNull)
  val SET_DEFAULT = ForeignKeyAction(dialect.fkSetDefault)

  val INNER = JoinType(dialect.innerJoin)
  val LEFT = JoinType(dialect.leftJoin)
  val RIGHT = JoinType(dialect.rightJoin)
  val FULL = JoinType(dialect.fullJoin)

  val OP_UNION = SetOperation(dialect.union)
  val OP_UNION_ALL = SetOperation(dialect.unionAll)
  val OP_EXCEPT = SetOperation(dialect.except)
  val OP_EXCEPT_ALL = SetOperation(dialect.exceptAll)
  val OP_INTERSECT = SetOperation(dialect.intersect)
  val OP_INTERSECT_ALL = SetOperation(dialect.intersectAll)

  // Predicates DSL

  def AND(predicates: Predicate*) =
    new AggregatePredicateHelper(predicates.head).AND(predicates.tail: _*)
  def OR(predicates: Predicate*) =
    new AggregatePredicateHelper(predicates.head).OR(predicates.tail: _*)
  def NOT(predicate: Predicate) =
    new SimpleExpression(dialect.not(predicate.toSql), predicate.parameters)
  def expr[T](expression: String): ExpressionProjection[T] =
    new ExpressionProjection[T](expression)
  def prepareExpr(expression: String, params: Pair[String, Any]*): SimpleExpression = {
    var sqlText = expression
    var parameters: Seq[Any] = Nil
    val paramsMap = Map[String, Any](params: _*)
    val pattern = Pattern.compile(":(\\w+)\\b")
    val matcher = pattern.matcher(expression)
    while(matcher.find) paramsMap.get(matcher.group(1)) match {
      case Some(param) => parameters ++= List(param)
      case _ => parameters ++= List(null)
    }
    sqlText = matcher.replaceAll("?")
    return new SimpleExpression(sqlText, parameters)
  }

  // Simple subqueries DSL

  def EXISTS(subquery: SQLQuery[_]) =
    new SubqueryExpression(dialect.exists, subquery)

  def NOT_EXISTS(subquery: SQLQuery[_]) =
    new SubqueryExpression(dialect.notExists, subquery)

  // Simple projections

  def COUNT(expr: String) =
    new ExpressionProjection[Long](dialect.count + "(" + expr + ")")
  def COUNT_DISTINCT(expr: String) =
    new ExpressionProjection[Long](
      dialect.count + "(" + dialect.distinct + " " + expr + ")")
  def MAX(expr: String) =
    new ExpressionProjection[Any](dialect.max + "(" + expr + ")")
  def MIN(expr: String) =
    new ExpressionProjection[Any](dialect.min + "(" + expr + ")")
  def SUM(expr: String) =
    new ExpressionProjection[Any](dialect.sum + "(" + expr + ")")
  def AVG(expr: String) =
    new ExpressionProjection[Any](dialect.avg + "(" + expr + ")")

  // Queries DSL

  def SELECT[T](p1: Projection[T], p2: Projection[_], pn: Projection[_]*) = {
    val projections = List(p1, p2) ++ pn
    new Select(new AliasMapProjection(projections))
  }
  def SELECT[T](projection: Projection[T]): Select[T] = new Select(projection)
  def INSERT_INTO[PK, R <: Record[PK, R]](relation: Relation[PK, R]) =
    new InsertSelectHelper(relation)
  def UPDATE[PK, R <: Record[PK, R]](node: RelationNode[PK, R]) =
    new Update(node)
  def DELETE[PK, R <: Record[PK, R]](node: RelationNode[PK, R]) =
    new Delete(node)

}