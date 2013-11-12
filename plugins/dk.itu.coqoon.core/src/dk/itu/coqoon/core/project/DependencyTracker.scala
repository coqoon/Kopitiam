/* DependencyTracker.scala
 * Make-like tracking of objects and their (possibly unresolved) dependencies
 * Copyright © 2013 Alexander Faithfull
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License. */

package dk.itu.coqoon.core.project

import org.eclipse.core.runtime.IPath

class DependencyTracker {
  import DependencyTracker._

  private var dependencies : Map[IPath, Seq[Dependency]] = Map()

  def resolveDependencies() : Set[IPath] = {
    var resolutions = Set[IPath]()
    for ((path, deps) <- dependencies if !isResolved(path))
      setDependencies(path, deps.map(_ match {
        case d @ (_, _, Some(_)) => d
        case d @ (identifier, resolver, None) =>
          val resolution = resolver(identifier)
          if (resolution != None)
            resolutions += path
          (identifier, resolver, resolution)
      }))
    resolutions
  }

  def unresolveDependencies(from_ : IPath*) =
    dependencies =
      for ((from, deps) <- dependencies)
        yield (from, if (!from_.contains(from)) deps
            else deps.map(d => (d._1, d._2, None)))
  def unresolveDependenciesUpon(to : IPath*) =
    dependencies =
      for ((from, deps) <- dependencies)
        yield (from, deps.map(dep => (dep._1, dep._2,
            if (dep._3.exists(to.contains)) None else dep._3)))

  def isResolved(path : IPath) = dependencies.get(path).map(
      deps => !deps.exists(_._3 == None)).getOrElse(false)

  def getResolved() : Seq[IPath] =
    dependencies.toSeq.map(a => a._1).filter(a => isResolved(a))
  def getUnresolved() : Seq[IPath] =
    dependencies.toSeq.map(a => a._1).filter(a => !isResolved(a))

  def getDependencies() = dependencies
  def getDependencies(from : IPath) = dependencies.getOrElse(from, Seq())
  def hasDependencies(from : IPath) = (dependencies.get(from) != None)
  def addDependency(from : IPath, to : Dependency) =
    (dependencies = dependencies + (from -> (getDependencies(from) :+ to)))
  def setDependencies(from : IPath, to : Seq[Dependency]) =
    (dependencies = dependencies + (from -> to))
  def clearDependencies(from : IPath) = (dependencies = dependencies - from)

  override def toString = getDependencies.map(
      a => (Seq(a._1.toPortableString) ++ a._2.map(dependencyToString)).
          mkString("\n\t\t")).mkString("DG\n\t", "\n\t", "")
}
object DependencyTracker {
  type Identifier = String
  type Resolver = Identifier => Option[IPath]
  type Dependency = (Identifier, Resolver, Option[IPath])

  def dependencyToString(d : Dependency) = d match {
    case d @ (identifier, _, None) => "[X] " + identifier
    case d @ (_, _, Some(path)) => "[O] " + path.toPortableString
  }
}