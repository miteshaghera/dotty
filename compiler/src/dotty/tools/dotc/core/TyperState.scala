package dotty.tools
package dotc
package core

import Types._
import Contexts._
import util.{SimpleIdentityMap, SimpleIdentitySet}
import reporting._
import config.Config
import config.Printers.constr
import collection.mutable
import java.lang.ref.WeakReference
import util.Stats
import Decorators._

import scala.annotation.internal.sharable

object TyperState {
  @sharable private var nextId: Int = 0
  def initialState() =
    TyperState()
      .init(null, OrderingConstraint.empty)
      .setReporter(new ConsoleReporter())
      .setCommittable(true)

  opaque type Snapshot = (Constraint, TypeVars)

  extension (ts: TyperState)
    def snapshot(): Snapshot = (ts.constraint, ts.ownedVars)

    def resetTo(state: Snapshot)(using Context): Unit =
      val (c, tvs) = state
      for tv <- tvs do if tv.isInstantiated then tv.resetInst(ts)
      ts.ownedVars = tvs
      ts.constraint = c
}

class TyperState() {

  private var myId: Int = _
  def id: Int = myId

  private var previous: TyperState /* | Null */ = _

  private var myReporter: Reporter = _

  def reporter: Reporter = myReporter

  /** A fresh type state with the same constraint as this one and the given reporter */
  def setReporter(reporter: Reporter): this.type = { myReporter = reporter; this }

  private var myConstraint: Constraint = _

  def constraint: Constraint = myConstraint
  def constraint_=(c: Constraint)(using Context): Unit = {
    if (Config.debugCheckConstraintsClosed && isGlobalCommittable) c.checkClosed()
    myConstraint = c
    if Config.checkConsistentVars && !ctx.reporter.errorsReported then
      c.checkConsistentVars()
  }

  private var previousConstraint: Constraint = _

  private var myIsCommittable: Boolean = _

  def isCommittable: Boolean = myIsCommittable

  def setCommittable(committable: Boolean): this.type =
    this.myIsCommittable = committable
    this

  def isGlobalCommittable: Boolean =
    isCommittable && (previous == null || previous.isGlobalCommittable)

  private var isCommitted: Boolean = _

  /** The set of uninstantiated type variables which have this state as their owning state */
  private var myOwnedVars: TypeVars = _
  def ownedVars: TypeVars = myOwnedVars
  def ownedVars_=(vs: TypeVars): Unit = myOwnedVars = vs

  /** Initializes all fields except reporter, isCommittable, which need to be
   *  set separately.
   */
  private[core] def init(previous: TyperState /* | Null */, constraint: Constraint): this.type =
    this.myId = TyperState.nextId
    TyperState.nextId += 1
    this.previous = previous
    this.myConstraint = constraint
    this.previousConstraint = constraint
    this.myOwnedVars = SimpleIdentitySet.empty
    this.isCommitted = false
    this

  /** A fresh typer state with the same constraint as this one. */
  def fresh(reporter: Reporter = StoreReporter(this.reporter)): TyperState =
    util.Stats.record("TyperState.fresh")
    TyperState().init(this, this.constraint)
      .setReporter(reporter)
      .setCommittable(this.isCommittable)

  /** The uninstantiated variables */
  def uninstVars: collection.Seq[TypeVar] = constraint.uninstVars

  /** The closest ancestor of this typer state (including possibly this typer state itself)
   *  which is not yet committed, or which does not have a parent.
   */
  def uncommittedAncestor: TyperState =
    if (isCommitted) previous.uncommittedAncestor else this

  /** Commit typer state so that its information is copied into current typer state
   *  In addition (1) the owning state of undetermined or temporarily instantiated
   *  type variables changes from this typer state to the current one. (2) Variables
   *  that were temporarily instantiated in the current typer state are permanently
   *  instantiated instead.
   *
   *  A note on merging: An interesting test case is isApplicableSafe.scala. It turns out that this
   *  requires a context merge using the new `&' operator. Sequence of actions:
   *  1) Typecheck argument in typerstate 1.
   *  2) Cache argument.
   *  3) Evolve same typer state (to typecheck other arguments, say)
   *     leading to a different constraint.
   *  4) Take typechecked argument in same state.
   *
   * It turns out that the merge is needed not just for
   * isApplicableSafe but also for (e.g. erased-lubs.scala) as well as
   * many parts of dotty itself.
   */
  def commit()(using Context): Unit = {
    Stats.record("typerState.commit")
    assert(isCommittable)
    val targetState = ctx.typerState
    if constraint ne targetState.constraint then
      Stats.record("typerState.commit.new constraint")
      constr.println(i"committing $this to $targetState, fromConstr = $constraint, toConstr = ${targetState.constraint}")
      if targetState.constraint eq previousConstraint then
        targetState.constraint = constraint
        if !ownedVars.isEmpty then ownedVars.foreach(targetState.includeVar)
      else
        targetState.mergeConstraintWith(this)
    targetState.gc()
    reporter.flush()
    isCommitted = true
  }

  /** Ensure that this constraint does not associate different TypeVars for the
   *  same type lambda than the `other` constraint. Do this by renaming type lambdas
   *  in this constraint and its predecessors where necessary.
   */
  def ensureNotConflicting(other: Constraint)(using Context): Unit =
    def hasConflictingTypeVarsFor(tl: TypeLambda) =
      constraint.typeVarOfParam(tl.paramRefs(0)) ne other.typeVarOfParam(tl.paramRefs(0))
        // Note: Since TypeVars are allocated in bulk for each type lambda, we only
        // have to check the first one to find out if some of them are different.
    val conflicting = constraint.domainLambdas.find(tl =>
      other.contains(tl) && hasConflictingTypeVarsFor(tl))
    for tl <- conflicting do
      val tl1 = constraint.ensureFresh(tl)
      for case (tvar: TypeVar, pref1) <- tl.paramRefs.map(constraint.typeVarOfParam).lazyZip(tl1.paramRefs) do
        tvar.setOrigin(pref1)
      var ts = this
      while ts.constraint.domainLambdas.contains(tl) do
        ts.constraint = ts.constraint.subst(tl, tl1)
        ts = ts.previous

  def mergeConstraintWith(that: TyperState)(using Context): Unit =
    that.ensureNotConflicting(constraint)
    constraint = constraint & (that.constraint, otherHasErrors = that.reporter.errorsReported)
    for tvar <- constraint.uninstVars do
      if !isOwnedAnywhere(this, tvar) then ownedVars += tvar
    for tl <- constraint.domainLambdas do
      if constraint.isRemovable(tl) then constraint = constraint.remove(tl)

  private def includeVar(tvar: TypeVar)(using Context): Unit =
    tvar.owningState = new WeakReference(this)
    ownedVars += tvar

  private def isOwnedAnywhere(ts: TyperState, tvar: TypeVar): Boolean =
    ts.ownedVars.contains(tvar) || ts.previous != null && isOwnedAnywhere(ts.previous, tvar)

  /** Make type variable instances permanent by assigning to `inst` field if
   *  type variable instantiation cannot be retracted anymore. Then, remove
   *  no-longer needed constraint entries.
   */
  def gc()(using Context): Unit =
    if !ownedVars.isEmpty then
      Stats.record("typerState.gc")
      val toCollect = new mutable.ListBuffer[TypeLambda]
      for tvar <- ownedVars do
        if !tvar.inst.exists then
          val inst = constraint.instType(tvar)
          if inst.exists then
            tvar.setInst(inst)
            val tl = tvar.origin.binder
            if constraint.isRemovable(tl) then toCollect += tl
      for tl <- toCollect do
        constraint = constraint.remove(tl)

  override def toString: String = {
    def ids(state: TyperState): List[String] =
      s"${state.id}${if (state.isCommittable) "" else "X"}" ::
        (if (state.previous == null) Nil else ids(state.previous))
    s"TS[${ids(this).mkString(", ")}]"
  }

  def stateChainStr: String = s"$this${if (previous == null) "" else previous.stateChainStr}"
}
