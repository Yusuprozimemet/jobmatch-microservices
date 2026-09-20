import {
  HomeHeroActions,
  HomeHeroPanel,
} from "@/components/home/HomeHeroPersonalization";

export default function Home() {
  return (
    <>
      <section className="hero-section">
        <div className="hero-content">
          <p className="hero-eyebrow">JOB SEARCH, WITH MORE CLARITY</p>

          <h1 className="hero-title">
            Find work
            <br />
            that actually
            <br />
            fits you.
          </h1>

          <p className="hero-description">
            Search relevant and fresh jobs, understand your match, and spend
            less time on outdated listings.
          </p>

          <form className="hero-search" action="/jobs">
            <label className="sr-only" htmlFor="job-search">
              Search by job title, keyword, or skill
            </label>

            <input
              id="job-search"
              name="q"
              type="search"
              placeholder="Job title, keyword, or skill"
            />

            <button type="submit">Find jobs</button>
          </form>

          <HomeHeroActions />
        </div>

        <HomeHeroPanel />
      </section>

      <section className="value-section">
        <article>
          <span>01</span>
          <h2>Relevant jobs</h2>
          <p>
            Focus on opportunities that better match your skills, preferences,
            and experience.
          </p>
        </article>

        <article>
          <span>02</span>
          <h2>Transparent matching</h2>
          <p>
            See why a job matches you and which important skills may still be
            missing.
          </p>
        </article>

        <article>
          <span>03</span>
          <h2>Fresh listings</h2>
          <p>
            Understand whether a listing is recent and worth your time before
            you apply.
          </p>
        </article>
      </section>
    </>
  );
}
