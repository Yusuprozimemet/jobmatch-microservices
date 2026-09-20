"use client";

import { useRouter } from "next/navigation";
import {
  type FormEvent,
  type KeyboardEvent,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";

import { useAuth } from "@/context/AuthContext";
import {
  ApiError,
  deleteCurrentUser,
  getCurrentUser,
  getJobFilters,
  getProfile,
  getSavedJobs,
  type JobFiltersResponse,
  updateCurrentUser,
  updateProfile,
} from "@/lib/api";
import { formatCategoryLabel } from "@/lib/category";
import {
  formatProfileSkillLabel,
  normalizeProfileSkillsForCompatibility,
  PROFILE_SKILL_CATEGORIES,
  PROFILE_SKILL_OPTIONS,
  PROFILE_SKILL_VALUES,
} from "@/lib/profile-skills";

const MIN_SKILLS = 5;
const MAX_SKILLS = 20;
const MAX_VISIBLE_SKILL_RESULTS = 8;

const EMPTY_FILTER_OPTIONS: JobFiltersResponse = {
  locations: [],
  categories: [],
  workModes: [],
  experienceLevels: [],
  employmentTypes: [],
};

function optionsWithCurrent(options: string[], currentValue: string): string[] {
  if (!currentValue || options.includes(currentValue)) {
    return options;
  }

  return [currentValue, ...options];
}

function parseSalaryPreference(value: string): number | null {
  const trimmedValue = value.trim();

  if (!trimmedValue) {
    return null;
  }

  if (!/^\d{1,8}(\.\d{1,2})?$/.test(trimmedValue)) {
    throw new Error("invalid-salary");
  }

  const salary = Number(trimmedValue);

  if (Number.isNaN(salary) || salary < 0) {
    throw new Error("invalid-salary");
  }

  return salary;
}

export default function ProfilePage() {
  const router = useRouter();
  const { user, isLoading, clearUser, refreshUser } = useAuth();
  const skillListboxId = useId();
  const skillPickerRef = useRef<HTMLFieldSetElement>(null);

  const [name, setName] = useState("");
  const [isSavingAccount, setIsSavingAccount] = useState(false);
  const [accountMessage, setAccountMessage] = useState("");
  const [accountError, setAccountError] = useState("");

  const [skills, setSkills] = useState<string[]>([]);
  const [skillSearch, setSkillSearch] = useState("");
  const [selectedSkillCategory, setSelectedSkillCategory] = useState("");
  const [activeSkillIndex, setActiveSkillIndex] = useState(0);
  const [isSkillResultsOpen, setIsSkillResultsOpen] = useState(false);
  const [category, setCategory] = useState("");
  const [preferredCity, setPreferredCity] = useState("");
  const [workMode, setWorkMode] = useState("");
  const [experienceLevel, setExperienceLevel] = useState("");
  const [employmentType, setEmploymentType] = useState("");
  const [salaryPreference, setSalaryPreference] = useState("");
  const [filterOptions, setFilterOptions] =
    useState<JobFiltersResponse>(EMPTY_FILTER_OPTIONS);
  const [isLoadingFilterOptions, setIsLoadingFilterOptions] = useState(true);
  const [filterOptionsError, setFilterOptionsError] = useState("");

  const [isLoadingProfile, setIsLoadingProfile] = useState(true);
  const [isSavingProfile, setIsSavingProfile] = useState(false);
  const [profileMessage, setProfileMessage] = useState("");
  const [profileError, setProfileError] = useState("");

  const [showConfirmation, setShowConfirmation] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");
  const [isExportingData, setIsExportingData] = useState(false);
  const [dataExportError, setDataExportError] = useState("");

  useEffect(() => {
    if (!isLoading && !user) {
      router.replace("/login");
      return;
    }

    if (user) {
      setName(user.name);
    }
  }, [isLoading, user, router]);

  useEffect(() => {
    if (!user) {
      return;
    }

    let isActive = true;

    async function loadProfile() {
      setIsLoadingProfile(true);
      setProfileError("");

      try {
        const profile = await getProfile();

        if (!isActive) {
          return;
        }

        setSkills(normalizeProfileSkillsForCompatibility(profile.skills ?? []));
        setCategory(profile.category ?? "");
        setPreferredCity(profile.preferredCity ?? "");
        setWorkMode(profile.workMode ?? "");
        setExperienceLevel(profile.experienceLevel ?? "");
        setEmploymentType(profile.employmentType ?? "");

        setSalaryPreference(
          profile.salaryPreference === null ||
            profile.salaryPreference === undefined
            ? ""
            : String(profile.salaryPreference),
        );
      } catch (error) {
        if (!isActive) {
          return;
        }

        if (error instanceof ApiError && error.status === 401) {
          clearUser();
          router.replace("/login");
          return;
        }

        setProfileError(
          "We could not load your job preferences. You can still edit your account information.",
        );
      } finally {
        if (isActive) {
          setIsLoadingProfile(false);
        }
      }
    }

    void loadProfile();

    return () => {
      isActive = false;
    };
  }, [user, clearUser, router]);

  const loadFilterOptions = useCallback(async () => {
    setIsLoadingFilterOptions(true);
    setFilterOptionsError("");

    try {
      const filters = await getJobFilters();
      setFilterOptions(filters);
    } catch {
      setFilterOptions(EMPTY_FILTER_OPTIONS);
      setFilterOptionsError(
        "Could not load profile options. Please try again.",
      );
    } finally {
      setIsLoadingFilterOptions(false);
    }
  }, []);

  const filteredSkills = useMemo(() => {
    if (skills.length >= MAX_SKILLS) {
      return [];
    }

    const query = skillSearch.trim().toLowerCase();

    if (!query && !selectedSkillCategory) {
      return [];
    }

    return PROFILE_SKILL_OPTIONS.filter((skill) => {
      if (skills.includes(skill.value)) {
        return false;
      }

      if (selectedSkillCategory && skill.category !== selectedSkillCategory) {
        return false;
      }

      if (!query) {
        return true;
      }

      return formatProfileSkillLabel(skill.value).toLowerCase().includes(query);
    }).slice(0, MAX_VISIBLE_SKILL_RESULTS);
  }, [skillSearch, selectedSkillCategory, skills]);

  const categoryOptions = useMemo(
    () => optionsWithCurrent(filterOptions.categories, category),
    [filterOptions.categories, category],
  );
  const preferredCityOptions = useMemo(
    () => optionsWithCurrent(filterOptions.locations, preferredCity),
    [filterOptions.locations, preferredCity],
  );
  const workModeOptions = useMemo(
    () => optionsWithCurrent(filterOptions.workModes, workMode),
    [filterOptions.workModes, workMode],
  );
  const experienceOptions = useMemo(
    () => optionsWithCurrent(filterOptions.experienceLevels, experienceLevel),
    [filterOptions.experienceLevels, experienceLevel],
  );
  const employmentTypeOptions = useMemo(
    () => optionsWithCurrent(filterOptions.employmentTypes, employmentType),
    [filterOptions.employmentTypes, employmentType],
  );
  const areFilterOptionsUnavailable =
    isLoadingFilterOptions || Boolean(filterOptionsError);

  useEffect(() => {
    void loadFilterOptions();
  }, [loadFilterOptions]);

  useEffect(() => {
    function handleDocumentPointerDown(event: PointerEvent) {
      if (
        skillPickerRef.current &&
        !skillPickerRef.current.contains(event.target as Node)
      ) {
        setIsSkillResultsOpen(false);
      }
    }

    document.addEventListener("pointerdown", handleDocumentPointerDown);

    return () => {
      document.removeEventListener("pointerdown", handleDocumentPointerDown);
    };
  }, []);

  function addSkill(skill: string) {
    if (skills.includes(skill) || !PROFILE_SKILL_VALUES.has(skill)) {
      return;
    }

    if (skills.length >= MAX_SKILLS) {
      setProfileError(`Select no more than ${MAX_SKILLS} skills.`);
      return;
    }

    setSkills((currentSkills) => [...currentSkills, skill]);
    setSkillSearch("");
    setActiveSkillIndex(0);
    setIsSkillResultsOpen(false);
    setProfileMessage("");
    setProfileError("");
  }

  function handleSkillSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (!skillResultsAreOpen || filteredSkills.length === 0) {
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveSkillIndex((currentIndex) =>
        Math.min(currentIndex + 1, filteredSkills.length - 1),
      );
      return;
    }

    if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveSkillIndex((currentIndex) => Math.max(currentIndex - 1, 0));
      return;
    }

    if (event.key === "Enter") {
      event.preventDefault();
      const selectedSkill =
        filteredSkills[Math.min(activeSkillIndex, filteredSkills.length - 1)];

      if (selectedSkill) {
        addSkill(selectedSkill.value);
      }

      return;
    }
  }

  function handleSkillPickerKeyDown(event: KeyboardEvent<HTMLFieldSetElement>) {
    if (event.key !== "Escape") {
      return;
    }

    event.preventDefault();
    setIsSkillResultsOpen(false);
    setActiveSkillIndex(0);
  }

  function removeSkill(skill: string) {
    setSkills((currentSkills) =>
      currentSkills.filter((currentSkill) => currentSkill !== skill),
    );
    setProfileMessage("");
    setProfileError("");
  }

  async function handleAccountSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!user) {
      return;
    }

    setAccountMessage("");
    setAccountError("");
    setIsSavingAccount(true);

    try {
      await updateCurrentUser({
        name: name.trim(),
        email: user.email,
      });

      await refreshUser();

      setAccountMessage("Your account information has been updated.");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearUser();
        router.replace("/login");
        return;
      }

      setAccountError(
        "We could not update your account information. Please try again.",
      );
    } finally {
      setIsSavingAccount(false);
    }
  }

  async function handleProfileSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    setProfileMessage("");
    setProfileError("");

    if (skills.length < MIN_SKILLS) {
      const remainingSkills = MIN_SKILLS - skills.length;
      setProfileError(
        `Select at least ${remainingSkills} more ${
          remainingSkills === 1 ? "skill" : "skills"
        }.`,
      );
      return;
    }

    if (skills.length > MAX_SKILLS) {
      setProfileError(`Select no more than ${MAX_SKILLS} skills.`);
      return;
    }

    let parsedSalaryPreference: number | null;

    try {
      parsedSalaryPreference = parseSalaryPreference(salaryPreference);
    } catch {
      setProfileError(
        "Salary preference must be zero or higher, with at most 8 digits before the decimal and 2 after.",
      );
      return;
    }

    setIsSavingProfile(true);

    try {
      const savedProfile = await updateProfile({
        skills,
        category: category || null,
        preferredCity: preferredCity.trim() || null,
        workMode: workMode || null,
        experienceLevel: experienceLevel || null,
        employmentType: employmentType || null,
        salaryPreference: parsedSalaryPreference,
      });

      setSkills(
        normalizeProfileSkillsForCompatibility(savedProfile.skills ?? skills),
      );
      setCategory(savedProfile.category ?? "");
      setPreferredCity(savedProfile.preferredCity ?? "");
      setWorkMode(savedProfile.workMode ?? "");
      setExperienceLevel(savedProfile.experienceLevel ?? "");
      setEmploymentType(savedProfile.employmentType ?? "");

      setSalaryPreference(
        savedProfile.salaryPreference === null ||
          savedProfile.salaryPreference === undefined
          ? ""
          : String(savedProfile.salaryPreference),
      );

      setProfileMessage("Your job preferences have been saved.");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearUser();
        router.replace("/login");
        return;
      }

      setProfileError(
        "We could not save your job preferences. Please try again.",
      );
    } finally {
      setIsSavingProfile(false);
    }
  }

  async function handleDeleteAccount() {
    setDeleteError("");
    setIsDeleting(true);

    try {
      await deleteCurrentUser();

      clearUser();
      router.replace("/");
      router.refresh();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearUser();
        router.replace("/login");
        return;
      }

      setDeleteError("We could not delete your account. Please try again.");
      setIsDeleting(false);
    }
  }

  async function handleDataExport() {
    setDataExportError("");
    setIsExportingData(true);

    try {
      const [currentUser, profile, savedJobs] = await Promise.all([
        getCurrentUser(),
        getProfile(),
        getSavedJobs(),
      ]);

      if (!currentUser) {
        clearUser();
        router.replace("/login");
        return;
      }

      const exportData = {
        exportedAt: new Date().toISOString(),
        user: currentUser,
        profile,
        savedJobs,
      };
      const blob = new Blob([JSON.stringify(exportData, null, 2)], {
        type: "application/json",
      });
      const objectUrl = URL.createObjectURL(blob);
      const downloadLink = document.createElement("a");

      downloadLink.href = objectUrl;
      downloadLink.download = "jobmatch-my-data.json";
      downloadLink.click();
      URL.revokeObjectURL(objectUrl);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearUser();
        router.replace("/login");
        return;
      }

      setDataExportError(
        "We could not prepare your data export. Please try again.",
      );
    } finally {
      setIsExportingData(false);
    }
  }

  const skillsNeeded = Math.max(MIN_SKILLS - skills.length, 0);
  const skillResultsAreOpen = isSkillResultsOpen && filteredSkills.length > 0;
  const safeActiveSkillIndex =
    filteredSkills.length > 0
      ? Math.min(activeSkillIndex, filteredSkills.length - 1)
      : 0;
  const activeSkill = skillResultsAreOpen
    ? filteredSkills[safeActiveSkillIndex]
    : undefined;

  if (isLoading || !user) {
    return null;
  }

  return (
    <div className="profile-page">
      <header className="profile-header">
        <p className="profile-eyebrow">YOUR PROFILE</p>

        <h1>Profile</h1>

        <p>
          Manage your account information and tell JobMatch what you are looking
          for.
        </p>
      </header>

      <section className="profile-section">
        <div className="profile-section-heading">
          <p>PERSONAL INFORMATION</p>
          <h2>Edit profile</h2>
          <p>
            Keep your account information up to date. Your email address cannot
            be changed here.
          </p>
        </div>

        <form className="profile-form" onSubmit={handleAccountSubmit}>
          <div className="profile-field">
            <label htmlFor="profile-name">Name</label>

            <input
              id="profile-name"
              type="text"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
            />
          </div>

          <div className="profile-field">
            <label htmlFor="profile-email">Email</label>

            <input
              id="profile-email"
              type="email"
              value={user.email}
              readOnly
              aria-describedby="profile-email-help"
            />

            <p className="profile-field-help" id="profile-email-help">
              Email changes require verification and are not supported yet.
            </p>
          </div>

          {accountMessage ? (
            <output className="profile-message">{accountMessage}</output>
          ) : null}

          {accountError ? (
            <p className="profile-error" role="alert">
              {accountError}
            </p>
          ) : null}

          <div className="profile-form-actions">
            <button
              className="profile-save-button"
              type="submit"
              disabled={isSavingAccount || !name.trim()}
            >
              {isSavingAccount ? "Saving..." : "Save account changes"}
            </button>
          </div>
        </form>
      </section>

      <section className="profile-section">
        <div className="profile-section-heading">
          <p>JOB PREFERENCES</p>

          <h2>What are you looking for?</h2>

          <p>
            Skills and preferred city are currently used for job matching. Other
            preferences are optional and may be used in future improvements.
          </p>
        </div>

        {isLoadingProfile ? (
          <>
            {/* biome-ignore lint/a11y/useSemanticElements: Loading copy should remain a normal text element with status semantics. */}
            <p className="profile-loading" role="status">
              Loading your preferences...
            </p>
          </>
        ) : (
          <form className="profile-form" onSubmit={handleProfileSubmit}>
            <div className="profile-field profile-field-full">
              <label htmlFor="skill-search">
                Skills
                <span className="profile-field-note">
                  Required for matching
                </span>
              </label>

              {skillsNeeded > 0 ? (
                <p className="profile-onboarding-note">
                  Complete your profile to unlock job matches. Add{" "}
                  {skillsNeeded} more {skillsNeeded === 1 ? "skill" : "skills"},
                  then save your preferences.
                </p>
              ) : null}

              <p className="profile-field-help">
                Choose 5 to 20 supported skills that best describe your
                experience. Search or browse by category.
              </p>

              <p className="profile-skill-count">
                {skills.length} / {MAX_SKILLS} selected
              </p>

              {skillsNeeded > 0 ? (
                <p className="profile-skill-empty">
                  Select at least {skillsNeeded} more{" "}
                  {skillsNeeded === 1 ? "skill" : "skills"}.
                </p>
              ) : null}

              {skills.length > 0 ? (
                <div className="profile-selected-skills">
                  {skills.map((skill) => (
                    <span className="profile-skill-tag" key={skill}>
                      {formatProfileSkillLabel(skill)}

                      <button
                        type="button"
                        aria-label={`Remove ${formatProfileSkillLabel(skill)}`}
                        onClick={() => removeSkill(skill)}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                </div>
              ) : null}

              <fieldset
                className="profile-skill-picker-group"
                ref={skillPickerRef}
                aria-label="Skill picker"
                onKeyDown={handleSkillPickerKeyDown}
              >
                <div className="profile-skill-category">
                  <label htmlFor="skill-category">Browse category</label>

                  <select
                    id="skill-category"
                    value={selectedSkillCategory}
                    onChange={(event) => {
                      setSelectedSkillCategory(event.target.value);
                      setActiveSkillIndex(0);
                      setIsSkillResultsOpen(true);
                    }}
                    disabled={skills.length >= MAX_SKILLS}
                  >
                    <option value="">All categories</option>

                    {PROFILE_SKILL_CATEGORIES.map((category) => (
                      <option key={category} value={category}>
                        {category}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="profile-skill-picker">
                  <input
                    id="skill-search"
                    type="search"
                    value={skillSearch}
                    onChange={(event) => {
                      setSkillSearch(event.target.value);
                      setActiveSkillIndex(0);
                      setIsSkillResultsOpen(true);
                    }}
                    onFocus={() => {
                      if (filteredSkills.length > 0) {
                        setIsSkillResultsOpen(true);
                      }
                    }}
                    onKeyDown={handleSkillSearchKeyDown}
                    placeholder="Search skills, e.g. React"
                    autoComplete="off"
                    disabled={skills.length >= MAX_SKILLS}
                    role="combobox"
                    aria-autocomplete="list"
                    aria-expanded={skillResultsAreOpen}
                    aria-controls={
                      skillResultsAreOpen ? skillListboxId : undefined
                    }
                    aria-activedescendant={
                      activeSkill
                        ? `profile-skill-option-${activeSkill.value}`
                        : undefined
                    }
                  />

                  {skillResultsAreOpen ? (
                    <div
                      className="profile-skill-options"
                      id={skillListboxId}
                      role="listbox"
                    >
                      {filteredSkills.map((skill, index) => (
                        <button
                          type="button"
                          id={`profile-skill-option-${skill.value}`}
                          key={skill.value}
                          onClick={() => addSkill(skill.value)}
                          onMouseEnter={() => setActiveSkillIndex(index)}
                          role="option"
                          aria-selected={index === safeActiveSkillIndex}
                        >
                          <span>{formatProfileSkillLabel(skill.value)}</span>
                          <small>{skill.category}</small>
                        </button>
                      ))}
                    </div>
                  ) : null}

                  {skills.length >= MAX_SKILLS ? (
                    <p className="profile-skill-empty">
                      You have selected the maximum of {MAX_SKILLS} skills.
                    </p>
                  ) : null}

                  {skillSearch.trim() &&
                  filteredSkills.length === 0 &&
                  skills.length < MAX_SKILLS &&
                  !skills.some(
                    (skill) =>
                      skill.toLowerCase() === skillSearch.trim().toLowerCase(),
                  ) ? (
                    <p className="profile-skill-empty">
                      No supported skill found.
                    </p>
                  ) : null}
                </div>
              </fieldset>
            </div>

            {isLoadingFilterOptions ? (
              <>
                {/* biome-ignore lint/a11y/useSemanticElements: Loading copy should remain a normal text element with status semantics. */}
                <p className="profile-loading" role="status">
                  Loading profile options...
                </p>
              </>
            ) : null}

            {filterOptionsError ? (
              <div className="profile-error" role="alert">
                <p>{filterOptionsError}</p>

                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => void loadFilterOptions()}
                >
                  Try again
                </button>
              </div>
            ) : null}

            <fieldset className="profile-fieldset">
              <legend>Role and experience</legend>

              <div className="profile-field">
                <label htmlFor="category">
                  Target role / category
                  <span className="profile-field-note">Optional</span>
                </label>

                <select
                  id="category"
                  value={category}
                  disabled={areFilterOptionsUnavailable}
                  onChange={(event) => setCategory(event.target.value)}
                >
                  <option value="">No preference</option>

                  {categoryOptions.map((option) => (
                    <option key={option} value={option}>
                      {formatCategoryLabel(option)}
                    </option>
                  ))}
                </select>
              </div>

              <div className="profile-field">
                <label htmlFor="experience-level">
                  Experience
                  <span className="profile-field-note">Optional</span>
                </label>

                <select
                  id="experience-level"
                  value={experienceLevel}
                  disabled={areFilterOptionsUnavailable}
                  onChange={(event) => setExperienceLevel(event.target.value)}
                >
                  <option value="">No preference</option>

                  {experienceOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>
            </fieldset>

            <fieldset className="profile-fieldset">
              <legend>Location and work style</legend>

              <div className="profile-field">
                <label htmlFor="preferred-city">
                  Preferred city
                  <span className="profile-field-note">
                    Required for matching
                  </span>
                </label>

                <select
                  id="preferred-city"
                  value={preferredCity}
                  disabled={areFilterOptionsUnavailable}
                  onChange={(event) => setPreferredCity(event.target.value)}
                >
                  <option value="">No preference</option>

                  {preferredCityOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>

              <div className="profile-field">
                <label htmlFor="work-mode">
                  Work mode
                  <span className="profile-field-note">Optional</span>
                </label>

                <select
                  id="work-mode"
                  value={workMode}
                  disabled={areFilterOptionsUnavailable}
                  onChange={(event) => setWorkMode(event.target.value)}
                >
                  <option value="">No preference</option>

                  {workModeOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>

              <div className="profile-field">
                <label htmlFor="employment-type">
                  Employment type
                  <span className="profile-field-note">Optional</span>
                </label>

                <select
                  id="employment-type"
                  value={employmentType}
                  disabled={areFilterOptionsUnavailable}
                  onChange={(event) => setEmploymentType(event.target.value)}
                >
                  <option value="">No preference</option>

                  {employmentTypeOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>
            </fieldset>

            <fieldset className="profile-fieldset profile-fieldset-compact">
              <legend>Additional preferences</legend>

              <div className="profile-field">
                <label htmlFor="salary-preference">
                  Yearly gross salary preference in euros
                  <span className="profile-field-note">Optional</span>
                </label>

                <input
                  id="salary-preference"
                  type="number"
                  min="0"
                  step="0.01"
                  value={salaryPreference}
                  onChange={(event) => setSalaryPreference(event.target.value)}
                  placeholder="e.g. 45000"
                />
              </div>
            </fieldset>

            {profileMessage ? (
              <output className="profile-message">{profileMessage}</output>
            ) : null}

            {profileError ? (
              <p className="profile-error" role="alert">
                {profileError}
              </p>
            ) : null}

            <div className="profile-form-actions">
              <button
                className="profile-save-button"
                type="submit"
                disabled={isSavingProfile}
              >
                {isSavingProfile ? "Saving..." : "Save preferences"}
              </button>
            </div>
          </form>
        )}
      </section>

      <section className="profile-section">
        <div className="profile-section-heading">
          <p>ACCOUNT</p>
          <h2>Account settings</h2>
        </div>

        <div className="data-export-panel">
          <div>
            <h3>Your data export</h3>

            <p>
              Download a JSON file with your account, profile, and saved jobs
              data.
            </p>
          </div>

          <button
            className="secondary-button"
            type="button"
            onClick={handleDataExport}
            disabled={isExportingData}
          >
            {isExportingData ? "Preparing..." : "Download all my data"}
          </button>

          {dataExportError ? (
            <p className="profile-error" role="alert">
              {dataExportError}
            </p>
          ) : null}
        </div>

        <div className="danger-zone">
          <div className="danger-zone-copy">
            <p className="danger-zone-label">DANGER ZONE</p>

            <h3>Delete your account</h3>

            <p>
              Permanently delete your account and all data associated with it.
              This action cannot be undone.
            </p>
          </div>

          {!showConfirmation ? (
            <button
              className="danger-button"
              type="button"
              onClick={() => setShowConfirmation(true)}
            >
              Delete account
            </button>
          ) : (
            <div className="delete-confirmation">
              <p className="delete-confirmation-title">
                Are you sure you want to delete your account?
              </p>

              <p className="delete-confirmation-copy">
                Your account and associated data will be permanently removed.
              </p>

              {deleteError ? (
                <p className="delete-error" role="alert">
                  {deleteError}
                </p>
              ) : null}

              <div className="delete-confirmation-actions">
                <button
                  className="danger-button"
                  type="button"
                  onClick={handleDeleteAccount}
                  disabled={isDeleting}
                >
                  {isDeleting ? "Deleting..." : "Yes, delete my account"}
                </button>

                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => {
                    setShowConfirmation(false);
                    setDeleteError("");
                  }}
                  disabled={isDeleting}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
