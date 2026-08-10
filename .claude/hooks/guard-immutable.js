#!/usr/bin/env node
// PreToolUse hook: deterministicky vynucuje dvě pravidla projektu (viz CLAUDE.md):
//   1. docs/archiv/ je jen ke čtení — žádné Edit/Write.
//   2. Commitnutá Flyway migrace se nikdy nemění (R-09/N-10) — nová, ještě
//      necommitnutá migrace (V38+ před prvním commitem) editovat lze.
// Vstup: JSON na stdin ({tool_name, tool_input, cwd}); blokace = exit 2 + stderr.
'use strict';
const { execFileSync } = require('child_process');

let raw = '';
process.stdin.on('data', (d) => (raw += d));
process.stdin.on('end', () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0); // nečitelný vstup — neblokovat
  }
  const fp = input.tool_input && input.tool_input.file_path;
  if (!fp) process.exit(0);
  const norm = String(fp).replace(/\\/g, '/');

  if (/\/docs\/archiv\//i.test(norm)) {
    console.error(
      'BLOKOVÁNO hookem: docs/archiv/ je jen ke čtení. Archivní dokumentace se ' +
        'needituje ani nemaže — aktuální dokumentace patří do docs/. Viz CLAUDE.md (Zákazy).'
    );
    process.exit(2);
  }

  if (/\/db\/(migration|demo|prod)\/V\d+__.+\.sql$/i.test(norm)) {
    try {
      execFileSync('git', ['ls-files', '--error-unmatch', '--', fp], {
        stdio: 'ignore',
        cwd: input.cwd || process.cwd(),
      });
      // soubor je v gitu → commitnutá migrace → neměnná
      console.error(
        'BLOKOVÁNO hookem: commitnutá Flyway migrace se NIKDY nemění (pravidlo R-09/N-10). ' +
          'Změnu proveď novou migrací V{n+1}__*.sql a aktualizuj docs/databaze.md. ' +
          'Viz docs/konvence.md §1. (Necommitnutou novou migraci editovat lze.)'
      );
      process.exit(2);
    } catch {
      // soubor v gitu není → nová migrace → povolit
    }
  }

  process.exit(0);
});
