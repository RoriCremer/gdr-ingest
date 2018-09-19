package org.broadinstitute.gdr.encode.steps.transform

import better.files.File
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2.{Pipe, Stream}
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import org.broadinstitute.gdr.encode.steps.IngestStep

import scala.language.higherKinds

class MergeFilesMetadata(
  files: File,
  replicates: File,
  experiments: File,
  targets: File,
  libraries: File,
  labs: File,
  samples: File,
  donors: File,
  override protected val out: File
) extends IngestStep {
  import MergeFilesMetadata._

  override def process[F[_]: Effect]: Stream[F, Unit] =
    Stream(
      replicates -> ReplicateFields,
      experiments -> ExperimentFields,
      targets -> TargetFields,
      libraries -> LibraryFields,
      labs -> LabFields,
      samples -> BiosampleFields,
      donors -> DonorFields
    ).evalMap((lookupTable[F] _).tupled)
      .fold(Map.empty[String, JsonObject])(_ ++ _)
      .flatMap { masterLookupTable =>
        val join = joinWithFile[F](masterLookupTable) _

        def chainJoin(
          prev: String,
          curr: String,
          fields: Set[String]
        ): Pipe[F, JsonObject, JsonObject] =
          _.evalMap(join(joinedName(curr, prev), fields, prev))

        IngestStep
          .readJsonArray(files)
          .evalMap(
            join(
              CollapseFileMetadata.ReplicateRefsField,
              ReplicateFields,
              ReplicatePrefix
            )
          )
          .through(chainJoin(ReplicatePrefix, ExperimentPrefix, ExperimentFields))
          .through(chainJoin(ReplicatePrefix, LibraryPrefix, LibraryFields))
          .through(chainJoin(ExperimentPrefix, TargetPrefix, TargetFields))
          .through(chainJoin(LibraryPrefix, BiosamplePrefix, BiosampleFields))
          .through(chainJoin(LibraryPrefix, LabPrefix, LabFields))
          .through(chainJoin(BiosamplePrefix, DonorPrefix, DonorFields))
      }
      .to(IngestStep.writeJsonArray(out))

  private def lookupTable[F[_]: Sync](
    metadata: File,
    keepFields: Set[String]
  ): F[Map[String, JsonObject]] =
    IngestStep
      .readJsonArray(metadata)
      .map { js =>
        for {
          id <- js("@id")
          idStr <- id.asString
        } yield {
          idStr -> js.filterKeys(keepFields.contains)
        }
      }
      .unNone
      .compile
      .fold(Map.empty[String, JsonObject])(_ + _)

  private def joinWithFile[F[_]: Sync](table: Map[String, JsonObject])(
    fkField: String,
    collectFields: Set[String],
    collectionPrefix: String
  )(file: JsonObject): F[JsonObject] = {
    val accumulatedFields = for {
      fkJson <- file(fkField)
      fks <- fkJson.as[List[String]].toOption
    } yield {
      fks.foldMap[Map[String, Set[Json]]] { fk =>
        table.get(fk).fold(Map.empty[String, Set[Json]]) { toJoin =>
          collectFields.map { f =>
            joinedName(f, collectionPrefix) -> toJoin(f).fold(Set.empty[Json])(Set(_))
          }.toMap
        }
      }
    }

    Sync[F]
      .fromOption(
        accumulatedFields,
        new IllegalStateException(s"No data found for field '$fkField'")
      )
      .map { fields =>
        JsonObject.fromMap(file.toMap ++ fields.mapValues(_.asJson))
      }
  }
}

object MergeFilesMetadata {

  val ReplicatePrefix = "replicate"
  val ReplicateFields = Set("experiment", "library", "uuid")

  val ExperimentPrefix = "experiment"
  val ExperimentFields = Set("accession", "assay_term_name", "target")

  val TargetPrefix = "target"
  val TargetFields = Set("label")

  val LibraryPrefix = "library"
  val LibraryFields = Set("accession", "biosample", "lab")

  val LabPrefix = "lab"
  val LabFields = Set("name")

  val BiosamplePrefix = "biosample"

  val BiosampleFields =
    Set("biosample_term_id", "biosample_term_name", "biosample_type", "donor")

  val DonorPrefix = "donor"
  val DonorFields = Set("accession")

  val JoinedSuffix = "_list"

  def joinedName(
    fieldName: String,
    joinedPrefix: String,
    withSuffix: Boolean = true
  ): String =
    s"${joinedPrefix}__$fieldName${if (withSuffix) JoinedSuffix else ""}"
}