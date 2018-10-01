package org.broadinstitute.gdr.encode.steps.google

import better.files.File
import cats.effect.Effect
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax._
import org.broadinstitute.gdr.encode.steps.IngestStep
import org.broadinstitute.gdr.encode.steps.transform.{
  CleanupFilesMetadata,
  DeriveActualUris
}

import scala.language.higherKinds

class BuildBqFilesJson(
  fileMetadata: File,
  rawStorageBucket: String,
  override protected val out: File
) extends IngestStep {

  override protected def process[F[_]: Effect]: Stream[F, Unit] =
    IngestStep
      .readJsonArray(fileMetadata)
      .filter(oneParticipant)
      .map(flattenParticipants)
      .map(swapUriFields)
      .map(sanitizeNames)
      .map(_.asJson.noSpaces)
      .to(IngestStep.writeLines(out))

  private def oneParticipant(fileJson: JsonObject): Boolean =
    fileJson(CleanupFilesMetadata.DonorFkField)
      .flatMap(_.asArray)
      .exists(_.length == 1)

  private def flattenParticipants(fileJson: JsonObject): JsonObject =
    fileJson(CleanupFilesMetadata.DonorFkField).flatMap(_.asArray).fold(fileJson) { ps =>
      fileJson.add(CleanupFilesMetadata.DonorFkField, ps.head)
    }

  private def swapUriFields(fileJson: JsonObject): JsonObject =
    fileJson(DeriveActualUris.DownloadUriField)
      .flatMap(_.asString)
      .fold(fileJson) { uri =>
        fileJson
          .add(Gcs.BlobPathField, Gcs.expectedStsUri(rawStorageBucket)(uri).asJson)
          .remove(DeriveActualUris.DownloadUriField)
      }

  private def sanitizeNames(fileJson: JsonObject): JsonObject =
    JsonObject.fromMap {
      fileJson.toMap.map {
        case (name, value) =>
          val noSuffix = if (name.endsWith("_list")) {
            name.dropRight(5)
          } else {
            name
          }

          noSuffix.replaceAll("__", "_") -> value
      }
    }
}
