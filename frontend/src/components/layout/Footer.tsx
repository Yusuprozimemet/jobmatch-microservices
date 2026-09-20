import Image from "next/image";
import Link from "next/link";

export default function Footer() {
  return (
    <footer className="site-footer">
      <div className="footer-inner">
        <div className="footer-brand">
          <Link
            className="brand footer-brand-link"
            href="/"
            aria-label="JobMatch home"
          >
            <Image
              className="footer-logo"
              src="/jobmatch-logo.svg"
              alt=""
              width={186}
              height={32}
            />
          </Link>

          <p>
            JobMatch — find jobs that fit you, and know which ones are worth
            your time
          </p>
        </div>

        <nav className="footer-nav" aria-label="Footer navigation">
          <Link href="/about">About</Link>
          <Link href="/about#contact">Contact</Link>
          <Link href="/privacy">Privacy</Link>
          <Link href="/terms">Terms</Link>
        </nav>

        <p className="footer-copyright">© 2026 JobMatch</p>
      </div>
    </footer>
  );
}
