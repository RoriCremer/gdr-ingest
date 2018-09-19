package org.broadinstitute.gdr.encode.steps

import better.files.File
import cats.effect.Effect
import cats.implicits._
import fs2.Stream
import org.broadinstitute.gdr.encode.steps.download._
import org.broadinstitute.gdr.encode.steps.transfer.BuildUrlManifest
import org.broadinstitute.gdr.encode.steps.transform._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

class PrepareIngest(override protected val out: File)(implicit ec: ExecutionContext)
    extends IngestStep {
  override def process[F[_]: Effect]: Stream[F, Unit] = {
    if (!out.isDirectory) {
      Stream.raiseError(
        new IllegalArgumentException(
          s"Download must be pointed at a directory, $out is not a directory"
        )
      )
    } else {
      val auditsOut = out / "audits.json"
      val biosamplesOut = out / "biosamples.json"
      val donorsOut = out / "donors.json"
      val experimentsOut = out / "experiments.json"
      val filesOut = out / "files.json"
      val labsOut = out / "labs.json"
      val librariesOut = out / "libraries.json"
      val replicatesOut = out / "replicates.json"
      val targetsOut = out / "targets.json"

      val collapsedFilesOut = out / "files.collapsed.json"
      val filesWithUris = out / "files.with-uris.json"
      val mergedFilesJson = out / "files.merged.json"

      val filesTableJson = out / "files.final.json"
      val donorsTableJson = out / "donors.final.json"
      val transferManifest = out / "sts-manifest.tsv"

      // FIXME: Implicit dependencies between steps would be better made explict.

      // Download metadata:
      val getExperiments = new GetExperiments(experimentsOut)
      val getAudits = new GetAudits(experimentsOut, auditsOut)
      val getReplicates = new GetReplicates(experimentsOut, replicatesOut)
      val getFiles = new GetFiles(experimentsOut, filesOut)
      val getTargets = new GetTargets(experimentsOut, targetsOut)
      val getLibraries = new GetLibraries(replicatesOut, librariesOut)
      val getLabs = new GetLabs(librariesOut, labsOut)
      val getSamples = new GetBiosamples(librariesOut, biosamplesOut)
      val getDonors = new GetDonors(biosamplesOut, donorsOut)

      // Transform & combine metadata:
      val collapseFileMetadata = new CollapseFileMetadata(filesOut, collapsedFilesOut)
      val deriveUris = new DeriveActualUris(collapsedFilesOut, filesWithUris)
      val mergeFileMetadata = new MergeFilesMetadata(
        files = filesWithUris,
        replicates = replicatesOut,
        experiments = experimentsOut,
        targets = targetsOut,
        libraries = librariesOut,
        labs = labsOut,
        samples = biosamplesOut,
        donors = donorsOut,
        out = mergedFilesJson
      )
      val mergeDonorMetadata = new MergeDonorsMetadata(
        donors = donorsOut,
        mergedFiles = filesTableJson,
        out = donorsTableJson
      )
      val cleanFileMetadata = new CleanupFilesMetadata(mergedFilesJson, filesTableJson)
      val buildTransferManifest = new BuildUrlManifest(filesTableJson, transferManifest)

      val run: F[Unit] = for {
        _ <- getExperiments.build
        _ <- parallelize(getAudits, getReplicates, getFiles, getTargets)
        _ <- getLibraries.build
        _ <- parallelize(getLabs, getSamples)
        _ <- parallelize(getDonors, collapseFileMetadata)
        _ <- deriveUris.build
        _ <- mergeFileMetadata.build
        _ <- mergeDonorMetadata.build
        _ <- parallelize(cleanFileMetadata, buildTransferManifest)
      } yield {
        ()
      }

      Stream.eval(run)
    }
  }

  private def parallelize[F[_]: Effect](steps: IngestStep*): F[Unit] =
    Stream
      .emits(steps)
      .map(_.build[F])
      .map(Stream.eval)
      .joinUnbounded
      .compile
      .drain
}
