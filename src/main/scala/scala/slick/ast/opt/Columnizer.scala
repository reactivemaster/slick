package scala.slick.ast
package opt

import Util._
import scala.slick.util.Logging
import scala.slick.ast.WithOp
import scala.collection.mutable.HashMap

/**
 * Expand columns in queries
 */
object Columnizer extends (Node => Node) with Logging {

  def apply(tree: Node): Node = {
    val t1 = forceOuterBind(tree)
    if(t1 ne tree) logger.debug("With outer binds:", t1)
    val t2 = expandTables(t1)
    if(t2 ne t1) logger.debug("Tables expanded:", t2)
    val t3 = expandRefs(t2)
    if(t3 ne t2) logger.debug("Refs expanded:", t3)
    val t4 = replaceFieldSymbols(t3)
    if(t4 ne t3) logger.debug("FieldSymbols replaced:", t4)
    t4
  }

  /** Ensure that all collection operations are wrapped in a Bind so that we
    * have a place for expanding references later. */
  def forceOuterBind(n: Node): Node = {
    def idBind(n: Node) = {
      val gen = new AnonSymbol
      logger.debug("Introducing new Bind "+gen)
      Bind(gen, n, Ref(gen))
    }
    def wrap(n: Node): Node = n match {
      case b: Bind => b.nodeMapChildren(nowrap)
      case n => idBind(n.nodeMapChildren(nowrap))
    }
    def nowrap(n: Node): Node = n match {
      case j: Join => j.nodeMapChildren { ch =>
        if((ch eq j.left) || (ch eq j.right)) nowrap(ch) else maybewrap(ch)
      }
      case u: Union => u.nodeMapChildren(nowrap)
      case f: FilteredQuery => f.nodeMapChildren { ch =>
        if(ch eq f.from) nowrap(ch) else maybewrap(ch)
      }
      case b: Bind => b.nodeMapChildren(nowrap)
      case n => n.nodeMapChildren(maybewrap)
    }
    def maybewrap(n: Node): Node = n match {
      case j: Join => wrap(n)
      case u: Union => wrap(n)
      case f: FilteredQuery => wrap(n)
      case n => nowrap(n)
    }
    wrap(n)
  }

  /** Replace all TableNodes with TableExpansions which contain both the
    * expansion and the original table */
  def expandTables(n: Node): Node = n match {
    case t: TableExpansion => t
    case t: TableNode =>
      val sym = new AnonSymbol
      val expanded = WithOp.encodeRef(t, sym).nodeShaped_*.packedNode
      val processed = expandTables(Optimizer.prepareTree(expanded, true))
      TableExpansion(sym, t, ProductNode(processed.flattenProduct: _*))
    case n => n.nodeMapChildren(expandTables)
  }

  /** Expand Paths to ProductNodes and TableExpansions into ProductNodes of
    * Paths and TableRefExpansions of Paths, so that all Paths point to
    * individual columns by index */
  def expandRefs(n: Node, scope: Scope = Scope.empty): Node = n match {
    case p @ PathOrRef(psyms) =>
      psyms.head match {
        case f: FieldSymbol => p
        case _ =>
        val syms = psyms.reverse
        scope.get(syms.head) match {
          case Some((target, _)) =>
            select(syms.tail, narrowStructure(target)).head match {
              case t: TableExpansion =>
                logger.debug("Narrowed "+p+" to "+t)
                burstPath(Ref(syms.head), syms.tail, t)
              case pr: ProductNode =>
                logger.debug("Narrowed "+p+" to "+pr)
                burstPath(Ref(syms.head), syms.tail, pr)
              case _ => p
            }
          case None => p
        }
      }
    case n => n.mapChildrenWithScope(expandRefs, scope)
  }

  /** Expand a path of selects into a given target on top of a base node */
  def burstPath(base: Node, selects: List[Symbol], target: Node): Node = target match {
    case ProductNode(ch @ _*) =>
      ProductNode(ch.zipWithIndex.map { case (n, idx) =>
        burstPath(Select(base, ElementSymbol(idx+1)), selects, n)
      }: _*)
    case TableExpansion(gen, t, cols) =>
      TableRefExpansion(new AnonSymbol, base, ProductNode(cols.nodeChildren.zipWithIndex.map { case (n, idx) =>
        burstPath(Select(base, ElementSymbol(idx+1)), selects, n)
      }: _*))
    case _ => selects.foldLeft(base){ case (z,sym) => Select(z, sym) }
  }

  /** Replace references to FieldSymbols in TableExpansions by the
    * appropriate ElementSymbol */
  def replaceFieldSymbols(n: Node): Node = {
    val updatedTables = new HashMap[Symbol, ProductNode]
    val seenDefs = new HashMap[Symbol, Node]

    def rewrite(target: Node, p: Node, field: FieldSymbol, syms: List[Symbol]): Option[Select] = {
      val ntarget = narrowStructure(target)
      logger.debug("Narrowed to structure "+ntarget)
      select(syms.tail, ntarget).map {
        case t: TableExpansion =>
          logger.debug("Narrowed to element "+t)
          val columns: ProductNode = updatedTables.get(t.generator).getOrElse(t.columns.asInstanceOf[ProductNode])
          val needed = Select(Ref(t.generator), field)
          Some(columns.nodeChildren.zipWithIndex.find(needed == _._1) match {
            case Some((_, idx)) => Select(p, ElementSymbol(idx+1))
            case None =>
              val col = Select(Ref(t.generator), field)
              updatedTables += t.generator -> ProductNode((columns.nodeChildren :+ col): _*)
              Select(p, ElementSymbol(columns.nodeChildren.size + 1))
          })
        case t: TableRefExpansion =>
          logger.debug("Narrowed to element "+t)
          PathOrRef.unapply(t.ref).flatMap { psyms =>
            val syms = psyms.reverse
            seenDefs.get(syms.head).flatMap { n =>
              logger.debug("Trying to rewrite recursive match "+t.ref+" ."+field)
              rewrite(n, t.ref, field, syms).map { recSel =>
                logger.debug("Found recursive replacement "+recSel.in+" ."+recSel.field)
                val columns: ProductNode = updatedTables.get(t.marker).getOrElse(t.columns.asInstanceOf[ProductNode])
                val needed = Select(t.ref, recSel.field)
                columns.nodeChildren.zipWithIndex.find(needed == _._1) match {
                  case Some((_, idx)) => Select(p, ElementSymbol(idx+1))
                  case None =>
                    val col = Select(t.ref, field)
                    updatedTables += t.marker -> ProductNode((columns.nodeChildren :+ col): _*)
                    Select(p, ElementSymbol(columns.nodeChildren.size + 1))
                }
              }
            }
          }

        case n => None // Can be a Table within a TableExpansion -> don't rewrite
      }.head // we have to assume that the structure is the same for all expansions
    }

    def tr(n: Node, scope: Scope = Scope.empty): Node = n match {
      case d: DefNode =>
        val r = d.mapChildrenWithScope(tr, scope)
        seenDefs ++= r.asInstanceOf[DefNode].nodeGenerators
        r
      case sel @ Select(p @ PathOrRef(psyms), field: FieldSymbol) =>
        val syms = psyms.reverse
        scope.get(syms.head).flatMap { case (n, _) =>
          logger.debug("Trying to rewrite "+p+" ."+field)
          val newSelO = rewrite(n, p, field, syms)
          newSelO.foreach(newSel => logger.debug("Replaced "+sel+" by "+newSel))
          newSelO
        }.getOrElse(sel)
      case n => n.mapChildrenWithScope(tr, scope)
    }

    val n2 = tr(n)
    val n3 = if(!updatedTables.isEmpty) {
      logger.debug("Patching "+updatedTables.size+" updated Table(Ref)Expansion(s) "+updatedTables.keysIterator.mkString(", ")+" into the tree")
      def update(n: Node): Node = n match {
        case t: TableExpansion =>
          updatedTables.get(t.generator).fold(t)(c => t.copy(columns = c)).nodeMapChildren(update)
        case t: TableRefExpansion =>
          updatedTables.get(t.marker).fold(t)(c => t.copy(columns = c)).nodeMapChildren(update)
        case n => n.nodeMapChildren(update)
      }
      update(n2)
    } else n2
    n3
  }

  /** Navigate into ProductNodes along a path */
  def select(selects: List[Symbol], base: Node): Vector[Node] = {
    logger.debug("select("+selects+", "+base+")")
    (selects, base) match {
      case (s, Union(l, r, _, _, _)) => select(s, l) ++ select(s, r)
      case (Nil, n) => Vector(n)
      case ((s: ElementSymbol) :: t, ProductNode(ch @ _*)) => select(t, ch(s.idx-1))
    }
  }

  /** Find the actual structure produced by a Node */
  def narrowStructure(n: Node): Node = n match {
    case Pure(n) => n
    case Join(_, _, l, r, _, _) => ProductNode(narrowStructure(l), narrowStructure(r))
    case u: Union => u.copy(left = narrowStructure(u.left), right = narrowStructure(u.right))
    case FilteredQuery(_, from) => narrowStructure(from)
    case Bind(_, _, select) => narrowStructure(select)
    case n => n
  }
}
