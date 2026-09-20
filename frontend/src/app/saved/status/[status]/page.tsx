import { notFound } from "next/navigation";
import SavedJobsContent from "@/components/jobs/SavedJobsContent";
import { isSavedJobStatus } from "@/lib/saved-job-status";

type SavedJobsStatusPageProps = {
  params: Promise<{
    status: string;
  }>;
};

export default async function SavedJobsStatusPage({
  params,
}: SavedJobsStatusPageProps) {
  const { status } = await params;
  const decodedStatus = decodeURIComponent(status);

  if (!isSavedJobStatus(decodedStatus)) {
    notFound();
  }

  return <SavedJobsContent status={decodedStatus} />;
}
