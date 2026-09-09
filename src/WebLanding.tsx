import "./WebLanding.css";
import { useTranslation } from "react-i18next";

const repositoryUrl = "https://github.com/YashasVM/Wisper-Low";
const releasesUrl = `${repositoryUrl}/releases`;

function WebLanding() {
  const { t } = useTranslation();

  return (
    <main className="web-landing">
      <div className="web-landing-grid" aria-hidden="true" />
      <nav
        className="web-landing-nav"
        aria-label={t("webLanding.primaryNavigation", {
          defaultValue: "Primary navigation",
        })}
      >
        <a
          className="web-landing-brand"
          href="/"
          aria-label={t("webLanding.home", {
            defaultValue: "Wisperlow home",
          })}
        >
          <span className="web-landing-mark" aria-hidden="true">
            {t("webLanding.brandMark", { defaultValue: "W" })}
          </span>
          <span>{t("webLanding.brand", { defaultValue: "Wisperlow" })}</span>
        </a>
        <a
          className="web-landing-github"
          href={repositoryUrl}
          target="_blank"
          rel="noreferrer"
        >
          {t("webLanding.github", { defaultValue: "GitHub" })}{" "}
          <span aria-hidden="true">↗</span>
        </a>
      </nav>

      <section className="web-landing-hero">
        <p className="web-landing-kicker">
          {t("webLanding.kicker", {
            defaultValue: "LOCAL DICTATION / OPEN SOURCE",
          })}
        </p>
        <h1>
          {t("webLanding.title", {
            defaultValue: "Speak. Let your machine handle the rest.",
          })}
        </h1>
        <p className="web-landing-copy">
          {t("webLanding.description", {
            defaultValue:
              "Wisperlow turns your voice into text locally, then puts it wherever your cursor is. No cloud detour. No microphone subscription.",
          })}
        </p>
        <div className="web-landing-actions">
          <a
            className="web-landing-primary"
            href={releasesUrl}
            target="_blank"
            rel="noreferrer"
          >
            {t("webLanding.download", { defaultValue: "Download Wisperlow" })}{" "}
            <span aria-hidden="true">↗</span>
          </a>
          <a
            className="web-landing-secondary"
            href={`${repositoryUrl}#readme`}
            target="_blank"
            rel="noreferrer"
          >
            {t("webLanding.readme", { defaultValue: "Read the README" })}
          </a>
        </div>
      </section>

      <section
        className="web-landing-notes"
        aria-label={t("webLanding.highlights", { defaultValue: "Highlights" })}
      >
        <article>
          <strong>
            {t("webLanding.localFirst.title", { defaultValue: "Local-first" })}
          </strong>
          <span>
            {t("webLanding.localFirst.description", {
              defaultValue: "Your audio stays on your machine.",
            })}
          </span>
        </article>
        <article>
          <strong>
            {t("webLanding.pushToTalk.title", { defaultValue: "Push to talk" })}
          </strong>
          <span>
            {t("webLanding.pushToTalk.description", {
              defaultValue: "Hold a shortcut, speak, release.",
            })}
          </span>
        </article>
        <article>
          <strong>
            {t("webLanding.openSource.title", { defaultValue: "Open source" })}
          </strong>
          <span>
            {t("webLanding.openSource.description", {
              defaultValue: "Inspect it, improve it, make it yours.",
            })}
          </span>
        </article>
      </section>

      <footer className="web-landing-footer">
        <span>
          {t("webLanding.byline", { defaultValue: "Wisperlow by Yashas VM" })}
        </span>
        <a href={repositoryUrl} target="_blank" rel="noreferrer">
          {t("webLanding.source", { defaultValue: "Source on GitHub" })}{" "}
          <span aria-hidden="true">↗</span>
        </a>
      </footer>
    </main>
  );
}

export default WebLanding;
