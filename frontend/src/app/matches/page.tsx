import MatchesContent from "@/components/jobs/MatchesContent";

export default function MatchesPage() {
  return (
    <main className="matches-page">
      <div className="matches-container">
        <header className="matches-header">
          <p className="jobs-eyebrow">MATCHED OPPORTUNITIES</p>
          <h1>Your job matches</h1>
          <p>
            Review all ranked matches for your current profile, ordered by
            relevance.
          </p>
        </header>

        <MatchesContent />
      </div>
    </main>
  );
}
