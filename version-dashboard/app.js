const LANES = ['snapshot', 'candidate', 'release'];
const ITERATIONS = ['previous', 'current', 'next'];
const STAGE_KEYS = LANES.flatMap(l => ITERATIONS.map(i => `${l}-${i}`));
const DEPLOY_LANES = [
  { id: 'playground', name: 'Playground', desc: 'Free-form developer playground',            stage: 'snapshot-next',     accent: 'slate'   },
  { id: 'dev',        name: 'Dev',        desc: 'Shared development environment',            stage: 'snapshot-current',  accent: 'sky'     },
  { id: 'sit',        name: 'SIT',        desc: 'System integration testing',                stage: 'candidate-next',    accent: 'violet'  },
  { id: 'uat',        name: 'UAT',        desc: 'User acceptance testing',                   stage: 'candidate-current', accent: 'amber'   },
  { id: 'mirror',     name: 'Mirror',     desc: 'Production mirror for shadow traffic',      stage: 'release-current',   accent: 'teal'    },
  { id: 'prod',       name: 'Prod',       desc: 'Production',                                stage: 'release-current',   accent: 'emerald' }
];
const STORAGE_KEY = 'vd.state';
const THEME_KEY = 'vd.theme';
const LANE_STAGES_KEY = 'vd.laneStages';
const USER_KEY = 'vd.user';
const SEED_VERSION = '2';           // bump this whenever projects.json changes to force re-seed
const SEED_VERSION_KEY = 'vd.seedVersion';

function defaultLaneStages() {
  return Object.fromEntries(DEPLOY_LANES.map(l => [l.id, l.stage]));
}

function clone(obj) {
  return JSON.parse(JSON.stringify(obj));
}

function emptyStages() {
  const stages = {};
  for (const k of STAGE_KEYS) stages[k] = { version: '', imageVersion: '', deps: {}, prs: [], lastUpdated: null, lastUpdatedBy: '', testsPassed: 0, testsTotal: 0 };
  return stages;
}

function emptyDepPin(depProject, stageKey) {
  const target = depProject?.stages?.[stageKey];
  return {
    version: target?.version || '',
    imageVersion: target?.imageVersion || ''
  };
}

function migrateProjects(projects) {
  for (const p of projects || []) {
    if (typeof p.imageTag !== 'string') p.imageTag = '';
    for (const k of STAGE_KEYS) {
      const stage = p.stages?.[k];
      if (!stage) continue;
      if (typeof stage.imageVersion !== 'string') stage.imageVersion = '';
      if (!Array.isArray(stage.prs)) stage.prs = [];
      if (!('lastUpdated' in stage)) stage.lastUpdated = null;
      if (typeof stage.lastUpdatedBy !== 'string') stage.lastUpdatedBy = '';
      if (typeof stage.testsPassed !== 'number') stage.testsPassed = 0;
      if (typeof stage.testsTotal !== 'number') stage.testsTotal = 0;
      if (!stage.deps || typeof stage.deps !== 'object') stage.deps = {};
      for (const [depId, val] of Object.entries(stage.deps)) {
        if (typeof val === 'string') {
          stage.deps[depId] = { version: val, imageVersion: '' };
        } else if (val && typeof val === 'object') {
          if (typeof val.version !== 'string') val.version = '';
          if (typeof val.imageVersion !== 'string') val.imageVersion = '';
        } else {
          stage.deps[depId] = { version: '', imageVersion: '' };
        }
      }
    }
  }
  return projects;
}

function dashboard() {
  return {
    projects: [],
    selectedId: null,
    activeTab: 'description',
    query: '',
    theme: 'light',
    addDepInput: '',
    loaded: false,
    saveTimer: null,
    changesOpen: false,
    changesStageKey: null,
    laneConfirmOpen: false,
    laneConfirmData: { laneId: '', laneName: '', from: '', to: '' },
    laneStages: defaultLaneStages(),
    currentUser: localStorage.getItem(USER_KEY) || '',

    LANES,
    ITERATIONS,
    STAGE_KEYS,
    DEPLOY_LANES,

    laneAccent(accent) {
      return {
        slate:   { ring: 'ring-slate-300 dark:ring-slate-700',     bg: 'bg-slate-50 dark:bg-slate-900/40',       text: 'text-slate-700 dark:text-slate-300',     dot: 'bg-slate-500',   pill: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200' },
        sky:     { ring: 'ring-sky-300 dark:ring-sky-700',         bg: 'bg-sky-50 dark:bg-sky-950/40',           text: 'text-sky-700 dark:text-sky-300',         dot: 'bg-sky-500',     pill: 'bg-sky-100 text-sky-700 dark:bg-sky-950/60 dark:text-sky-300' },
        violet:  { ring: 'ring-violet-300 dark:ring-violet-700',   bg: 'bg-violet-50 dark:bg-violet-950/40',     text: 'text-violet-700 dark:text-violet-300',   dot: 'bg-violet-500',  pill: 'bg-violet-100 text-violet-700 dark:bg-violet-950/60 dark:text-violet-300' },
        amber:   { ring: 'ring-amber-300 dark:ring-amber-700',     bg: 'bg-amber-50 dark:bg-amber-950/40',       text: 'text-amber-700 dark:text-amber-300',     dot: 'bg-amber-500',   pill: 'bg-amber-100 text-amber-700 dark:bg-amber-950/60 dark:text-amber-300' },
        teal:    { ring: 'ring-teal-300 dark:ring-teal-700',       bg: 'bg-teal-50 dark:bg-teal-950/40',         text: 'text-teal-700 dark:text-teal-300',       dot: 'bg-teal-500',    pill: 'bg-teal-100 text-teal-700 dark:bg-teal-950/60 dark:text-teal-300' },
        emerald: { ring: 'ring-emerald-300 dark:ring-emerald-700', bg: 'bg-emerald-50 dark:bg-emerald-950/40',   text: 'text-emerald-700 dark:text-emerald-300', dot: 'bg-emerald-500', pill: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300' }
      }[accent] || this.laneAccent('slate');
    },

    async init() {
      const savedTheme = localStorage.getItem(THEME_KEY);
      if (savedTheme) {
        this.theme = savedTheme;
      } else if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
        this.theme = 'dark';
      }
      this.applyTheme();

      const savedSeedVer = localStorage.getItem(SEED_VERSION_KEY);
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved && savedSeedVer === SEED_VERSION) {
        try {
          this.projects = migrateProjects(JSON.parse(saved).projects);
        } catch (e) {
          console.error('Failed to parse saved state, reseeding', e);
          await this.loadSeed();
        }
      } else {
        // Either no saved state, or seed data has been updated — fetch fresh
        await this.loadSeed();
      }

      const savedLanes = localStorage.getItem(LANE_STAGES_KEY);
      if (savedLanes) {
        try {
          this.laneStages = { ...defaultLaneStages(), ...JSON.parse(savedLanes) };
        } catch (e) {
          console.error('Failed to parse lane stages, using defaults', e);
        }
      }

      if (this.projects.length && !this.selectedId) {
        this.selectedId = this.projects[0].id;
      }
      this.loaded = true;

      this.$watch('projects', () => this.scheduleSave(), { deep: true });
      this.$watch('laneStages', () => this.saveLaneStages(), { deep: true });

      this.$nextTick(() => {
        if (window.lucide) window.lucide.createIcons();
      });
    },

    async loadSeed() {
      try {
        const res = await fetch('projects.json?v=' + SEED_VERSION);
        const data = await res.json();
        this.projects = migrateProjects(data.projects);
        localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION);
      } catch (e) {
        console.error('Failed to fetch projects.json', e);
        this.projects = [];
      }
    },

    scheduleSave() {
      if (this.saveTimer) clearTimeout(this.saveTimer);
      this.saveTimer = setTimeout(() => this.save(), 250);
    },

    save() {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ projects: this.projects }));
      localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION);
    },

    saveLaneStages() {
      localStorage.setItem(LANE_STAGES_KEY, JSON.stringify(this.laneStages));
    },

    setLaneStage(laneId, stageKey) {
      if (!STAGE_KEYS.includes(stageKey)) return;
      this.laneStages[laneId] = stageKey;
    },

    resetLaneStages() {
      this.laneStages = defaultLaneStages();
    },

    refreshIcons() {
      this.$nextTick(() => {
        if (window.lucide) window.lucide.createIcons();
      });
    },

    toggleTheme() {
      this.theme = this.theme === 'dark' ? 'light' : 'dark';
      localStorage.setItem(THEME_KEY, this.theme);
      this.applyTheme();
    },

    applyTheme() {
      const root = document.documentElement;
      if (this.theme === 'dark') root.classList.add('dark');
      else root.classList.remove('dark');
    },

    get filteredProjects() {
      const q = this.query.trim().toLowerCase();
      const list = q
        ? this.projects.filter(p =>
            p.id.toLowerCase().includes(q) ||
            p.name.toLowerCase().includes(q)
          )
        : [...this.projects];
      return list.sort((a, b) => a.id.localeCompare(b.id));
    },

    get selectedProject() {
      return this.projects.find(p => p.id === this.selectedId) || null;
    },

    selectProject(id) {
      this.selectedId = id;
      this.activeTab = 'description';
      this.refreshIcons();
    },

    setTab(tab) {
      this.activeTab = tab;
      this.refreshIcons();
    },

    initials(name) {
      if (!name) return '?';
      return name.split(/\s+/).map(s => s[0]).filter(Boolean).slice(0, 2).join('').toUpperCase();
    },

    laneColor(lane) {
      return {
        snapshot:  { ring: 'ring-sky-300 dark:ring-sky-700',   bg: 'bg-sky-50 dark:bg-sky-950/40',     text: 'text-sky-700 dark:text-sky-300',     dot: 'bg-sky-500' },
        candidate: { ring: 'ring-amber-300 dark:ring-amber-700', bg: 'bg-amber-50 dark:bg-amber-950/40', text: 'text-amber-700 dark:text-amber-300', dot: 'bg-amber-500' },
        release:   { ring: 'ring-emerald-300 dark:ring-emerald-700', bg: 'bg-emerald-50 dark:bg-emerald-950/40', text: 'text-emerald-700 dark:text-emerald-300', dot: 'bg-emerald-500' },
      }[lane];
    },

    canPromote(stageKey) {
      const [lane, iter] = stageKey.split('-');
      if (iter === 'next') return true;
      if (iter === 'current') return lane !== 'release';
      return false;
    },

    promoteLabel(stageKey) {
      const [lane, iter] = stageKey.split('-');
      if (iter === 'next' && lane === 'release') return 'Release';
      if (iter === 'next') return `Promote to ${lane}-current`;
      if (iter === 'current' && lane !== 'release') {
        const next = LANES[LANES.indexOf(lane) + 1];
        return `Promote to ${next}-next`;
      }
      return '';
    },

    touchStage(projectId, stageKey) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project?.stages?.[stageKey]) return;
      project.stages[stageKey].lastUpdated = new Date().toISOString();
      project.stages[stageKey].lastUpdatedBy = this.currentUser;
    },

    formatDate(iso) {
      if (!iso) return '—';
      const d = new Date(iso);
      return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
        + ' ' + d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
    },

    saveUser() {
      localStorage.setItem(USER_KEY, this.currentUser);
    },

    testPassRate(stageKey) {
      const stage = this.selectedProject?.stages?.[stageKey];
      if (!stage || !stage.testsTotal) return 0;
      return stage.testsPassed / stage.testsTotal;
    },

    testDash(stageKey) {
      const circ = 2 * Math.PI * 13; // r=13 → ≈81.68
      const stage = this.selectedProject?.stages?.[stageKey];
      if (!stage?.testsTotal) return `0 ${circ.toFixed(2)}`;
      return `${(this.testPassRate(stageKey) * circ).toFixed(2)} ${circ.toFixed(2)}`;
    },

    testPassColor(stageKey) {
      const stage = this.selectedProject?.stages?.[stageKey];
      if (!stage?.testsTotal) return '#94a3b8';
      const r = this.testPassRate(stageKey);
      if (r >= 0.9) return '#22c55e';
      if (r >= 0.7) return '#f59e0b';
      return '#ef4444';
    },

    promote(projectId, stageKey) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return;
      const [lane, iter] = stageKey.split('-');

      if (iter === 'next') {
        project.stages[`${lane}-previous`] = clone(project.stages[`${lane}-current`]);
        project.stages[`${lane}-current`] = clone(project.stages[`${lane}-next`]);
        this.rebase(projectId, `${lane}-current`);
        this.touchStage(projectId, `${lane}-current`);
      } else if (iter === 'current') {
        const idx = LANES.indexOf(lane);
        if (idx === LANES.length - 1) return;
        const nextLane = LANES[idx + 1];
        project.stages[`${nextLane}-next`] = clone(project.stages[`${lane}-current`]);
        this.rebase(projectId, `${nextLane}-next`);
        this.touchStage(projectId, `${nextLane}-next`);
      }
      this.refreshIcons();
    },

    rollback(projectId, stageKey) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return;
      const [lane] = stageKey.split('-');
      // previous → current, current → next, clear previous
      project.stages[`${lane}-next`]     = clone(project.stages[`${lane}-current`]);
      project.stages[`${lane}-current`]  = clone(project.stages[`${lane}-previous`]);
      project.stages[`${lane}-previous`] = emptyStages()[`${lane}-previous`];
      this.rebase(projectId, `${lane}-current`);
      this.touchStage(projectId, `${lane}-current`);
      this.touchStage(projectId, `${lane}-next`);
      this.refreshIcons();
    },

    rebase(projectId, stageKey) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return;
      const stage = project.stages[stageKey];
      if (!stage) return;
      stage.deps = stage.deps || {};
      for (const depId of project.dependencies) {
        this._rebaseCell(project, stageKey, depId);
      }
      this.touchStage(projectId, stageKey);
    },

    rebaseDep(projectId, depId) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return;
      if (!project.dependencies.includes(depId)) return;
      for (const sk of STAGE_KEYS) this._rebaseCell(project, sk, depId);
    },

    rebaseCell(projectId, stageKey, depId) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return;
      this._rebaseCell(project, stageKey, depId);
    },

    rebaseAll(projectId) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return;
      for (const sk of STAGE_KEYS) {
        for (const depId of project.dependencies) {
          this._rebaseCell(project, sk, depId);
        }
      }
    },

    _rebaseCell(project, stageKey, depId) {
      const stage = project.stages[stageKey];
      if (!stage) return;
      stage.deps = stage.deps || {};
      const depProject = this.projects.find(p => p.id === depId);
      const target = depProject?.stages?.[stageKey];
      if (!target) return;
      stage.deps[depId] = {
        version: target.version || '',
        imageVersion: target.imageVersion || ''
      };
    },

    depDrift(projectId, stageKey, depId) {
      const fields = this.depDriftFields(projectId, stageKey, depId);
      return fields.version || fields.image;
    },

    depDriftFields(projectId, stageKey, depId) {
      const project = this.projects.find(p => p.id === projectId);
      if (!project) return { version: false, image: false };
      const pinned = project.stages[stageKey]?.deps?.[depId];
      const depProject = this.projects.find(p => p.id === depId);
      const actual = depProject?.stages?.[stageKey];
      if (!pinned || !actual) return { version: false, image: false };
      return {
        version: (pinned.version || '') !== (actual.version || ''),
        image: (pinned.imageVersion || '') !== (actual.imageVersion || '')
      };
    },

    addProject() {
      const id = prompt('Project id (e.g. team.service):');
      if (!id) return;
      if (this.projects.some(p => p.id === id)) {
        alert('A project with that id already exists.');
        return;
      }
      this.projects.push({
        id,
        name: id,
        description: '',
        leadDevelopers: [],
        githubRepo: '',
        imageTag: '',
        artifacts: [],
        dependencies: [],
        stages: emptyStages()
      });
      this.selectProject(id);
    },

    deleteProject(id) {
      if (!confirm(`Delete project "${id}"? This cannot be undone (until reset).`)) return;
      for (const p of this.projects) {
        const i = p.dependencies.indexOf(id);
        if (i >= 0) p.dependencies.splice(i, 1);
        for (const k of STAGE_KEYS) {
          if (p.stages[k]?.deps && id in p.stages[k].deps) delete p.stages[k].deps[id];
        }
      }
      this.projects = this.projects.filter(p => p.id !== id);
      this.selectedId = this.projects.length ? this.projects[0].id : null;
      this.refreshIcons();
    },

    addLeadDev(project) {
      project.leadDevelopers.push({ name: '', email: '' });
      this.refreshIcons();
    },
    removeLeadDev(project, idx) {
      project.leadDevelopers.splice(idx, 1);
      this.refreshIcons();
    },

    addArtifact(project) {
      project.artifacts.push({ name: '', url: '' });
      this.refreshIcons();
    },
    removeArtifact(project, idx) {
      project.artifacts.splice(idx, 1);
      this.refreshIcons();
    },

    addDependency(project, depId) {
      const id = (depId || '').trim();
      if (!id) return;
      if (id === project.id) { alert('A project cannot depend on itself.'); return; }
      if (!this.projects.some(p => p.id === id)) { alert(`No project with id "${id}".`); return; }
      if (project.dependencies.includes(id)) return;
      project.dependencies.push(id);
      const depProject = this.projects.find(p => p.id === id);
      for (const k of STAGE_KEYS) {
        if (!project.stages[k].deps) project.stages[k].deps = {};
        project.stages[k].deps[id] = emptyDepPin(depProject, k);
      }
      this.addDepInput = '';
      this.refreshIcons();
    },

    removeDependency(project, depId) {
      project.dependencies = project.dependencies.filter(d => d !== depId);
      for (const k of STAGE_KEYS) {
        if (project.stages[k]?.deps && depId in project.stages[k].deps) {
          delete project.stages[k].deps[depId];
        }
      }
      this.refreshIcons();
    },

    availableDeps(project) {
      return this.projects
        .map(p => p.id)
        .filter(id => id !== project.id && !project.dependencies.includes(id));
    },

    openChanges(stageKey) {
      this.changesStageKey = stageKey;
      this.changesOpen = true;
      this.refreshIcons();
    },

    closeChanges() {
      this.changesOpen = false;
      this.changesStageKey = null;
    },

    openLaneConfirm(laneId, laneName, from, to) {
      this.laneConfirmData = { laneId, laneName, from, to };
      this.laneConfirmOpen = true;
    },
    confirmLaneChange() {
      this.setLaneStage(this.laneConfirmData.laneId, this.laneConfirmData.to);
      this.laneConfirmOpen = false;
    },
    closeLaneConfirm() {
      this.laneConfirmOpen = false;
    },

    get changesPrs() {
      if (!this.selectedProject || !this.changesStageKey) return [];
      return this.selectedProject.stages[this.changesStageKey]?.prs || [];
    },

    exportJson() {
      const blob = new Blob(
        [JSON.stringify({ projects: this.projects }, null, 2)],
        { type: 'application/json' }
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'projects.json';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    },

    async resetToSeed() {
      if (!confirm('Reset all data to seed projects.json? Your edits will be lost.')) return;
      localStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem(SEED_VERSION_KEY);
      localStorage.removeItem(LANE_STAGES_KEY);
      this.resetLaneStages();
      await this.loadSeed();
      this.selectedId = this.projects.length ? this.projects[0].id : null;
      this.activeTab = 'description';
      this.refreshIcons();
    }
  };
}

window.dashboard = dashboard;
