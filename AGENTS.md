# Project Instructions

- Read `SPEC.md` completely before making architectural or implementation decisions.
- Implement the system described in `SPEC.md`.
- Use Java 25 and Spring Boot 4.
- Keep the project buildable throughout implementation.
- Run relevant builds and tests after major changes and fix failures before moving on.
- Do not stop at planning, architecture discussion, or pseudocode; modify the repository and implement the system.
- Prefer simple, readable, maintainable solutions over unnecessary abstractions.
- Do not introduce microservices, Kubernetes, reactive programming, or large frameworks unless `SPEC.md` clearly requires them.
- Prefer standard Java 25 features over Lombok where practical.
- Do not use preview Java features unless there is a clear benefit and the requirement is documented.
- Preserve existing repository conventions when they do not conflict with `SPEC.md`.
- If `SPEC.md` conflicts with an assumption, follow `SPEC.md`.
- If a non-critical decision is unspecified, choose a sensible default, document it, and continue.
- Ask the user only when a genuinely blocking decision cannot be resolved from the repository or `SPEC.md`.
- Never commit real API keys, credentials, or secrets.
- Do not claim a feature works unless the relevant build/test was actually run where possible.
