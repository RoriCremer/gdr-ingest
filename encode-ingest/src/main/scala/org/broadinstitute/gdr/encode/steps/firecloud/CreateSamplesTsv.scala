package org.broadinstitute.gdr.encode.steps.firecloud

import better.files.File
import cats.effect.Effect
import fs2.Stream
import io.circe.{Json, JsonObject}
import org.broadinstitute.gdr.encode.steps.IngestStep
import org.broadinstitute.gdr.encode.steps.google.Gcs

import scala.language.higherKinds

class CreateSamplesTsv(
  filesJson: File,
  rawStorageBucket: String,
  override protected val out: File
) extends IngestStep {
  import org.broadinstitute.gdr.encode.EncodeFields._

  override protected def process[F[_]: Effect]: Stream[F, Unit] = {

    val fileRows =
      IngestStep
        .readJsonArray(filesJson)
        .filter(oneParticipant)
        .map(buildRow(CreateSamplesTsv.ObjectFields))

    Stream
      .emit(CreateSamplesTsv.TsvHeaders)
      .append(fileRows)
      .map(_.mkString("\t"))
      .to(IngestStep.writeLines(out))
  }

  private def oneParticipant(fileJson: JsonObject): Boolean =
    fileJson(DonorFkField)
      .flatMap(_.asArray)
      .exists(_.length == 1)

  private def buildRow(fields: List[String])(fileJson: JsonObject): List[String] =
    fields.foldRight(List.empty[String]) { (field, acc) =>
      val rawValue = fileJson(field).getOrElse(Json.fromString(""))
      val tsvValue = field match {
        case DownloadUriField =>
          rawValue.mapString(Gcs.expectedStsUri(rawStorageBucket))
        case DonorFkField => rawValue.withArray(_.head)
        case _            => rawValue
      }

      tsvValue.noSpaces :: acc
    }
}

object CreateSamplesTsv {
  import org.broadinstitute.gdr.encode.EncodeFields._

  val TsvHeaders = List.concat(
    List("entity:sample_id", "participant_id", Gcs.BlobPathField),
    FinalFileFields -- Set(FileAccessionField, DonorFkField, "href")
  )

  val ObjectFields = List.concat(
    List(FileAccessionField, DonorFkField, DownloadUriField),
    TsvHeaders.drop(3)
  )
}
