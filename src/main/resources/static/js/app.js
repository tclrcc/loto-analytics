document.addEventListener('DOMContentLoaded', () => {

    // --- Variables Globales ---
    let currentChart = null;
    let rawData = [];
    let activeTab = 'main';

    // --- Initialisation ---
    setupEventListeners();
    chargerStats();

    // --- Gestionnaires d'événements ---
    function setupEventListeners() {

        // 1. Import CSV
        const btnImport = document.getElementById('btnImport');
        if (btnImport) btnImport.addEventListener('click', uploadCsv);

        // 2. Bouton Magique (Pronostic Standard)
        const btnMagic = document.getElementById('btnMagic');
        if (btnMagic) btnMagic.addEventListener('click', genererPronostic);

        // 3. Simulateur
        const formSimu = document.getElementById('formSimulateur');
        if (formSimu) formSimu.addEventListener('submit', analyserGrille);

        // 4. Filtres Jours
        document.querySelectorAll('.btn-filter').forEach(btn => {
            btn.addEventListener('click', (e) => {
                document.querySelectorAll('.btn-filter').forEach(b => b.classList.remove('active'));
                const target = e.target.closest('.btn');
                target.classList.add('active');
                chargerStats(target.getAttribute('data-jour'));
            });
        });

        // 5. Algorithme Hybride (Astro + Stats)
        const btnHybrid = document.getElementById('btnHybridAlgo');
        if (btnHybrid) {
            btnHybrid.addEventListener('click', async () => {
                const profil = {
                    dateNaissance: document.getElementById('astroDate').value,
                    timeNaissance: document.getElementById('astroTime').value,
                    ville: document.getElementById('astroVille').value,
                    signe: document.getElementById('astroSigne').value
                };

                const dateSimuInput = document.getElementById('simDate');
                if(!dateSimuInput.value) dateSimuInput.valueAsDate = new Date();
                const dateCible = dateSimuInput.value;

                if(!profil.dateNaissance || !profil.signe) {
                    alert("Veuillez remplir vos informations de naissance."); return;
                }

                const originalText = btnHybrid.innerHTML;
                btnHybrid.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Fusion en cours...';
                btnHybrid.disabled = true;

                try {
                    const res = await fetch(`/api/loto/generate-hybrid?date=${dateCible}&count=5`, {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(profil)
                    });

                    if(!res.ok) throw new Error();
                    const data = await res.json();

                    const modalEl = document.getElementById('modalAstro');
                    const modal = bootstrap.Modal.getInstance(modalEl);
                    modal.hide();

                    afficherMultiplesPronostics(data, dateCible);
                    document.getElementById('pronoResult').scrollIntoView({behavior:'smooth'});

                } catch(e) {
                    console.error(e);
                    alert("Erreur lors du calcul hybride.");
                } finally {
                    btnHybrid.innerHTML = originalText;
                    btnHybrid.disabled = false;
                }
            });
        }

        // 6. Ajout Manuel
        const formManuel = document.getElementById('formManuel');
        if (formManuel) formManuel.addEventListener('submit', ajouterTirageManuel);

        // 7. Astro Simple
        const formAstro = document.getElementById('formAstro');
        const btnCopyAstro = document.getElementById('btnCopyAstro');

        if(formAstro) {
            formAstro.addEventListener('submit', async (e) => {
                e.preventDefault();
                const payload = {
                    dateNaissance: document.getElementById('astroDate').value,
                    timeNaissance: document.getElementById('astroTime').value,
                    ville: document.getElementById('astroVille').value,
                    signe: document.getElementById('astroSigne').value
                };
                const btn = formAstro.querySelector('button[type="submit"]');
                const originalText = btn.innerHTML;
                btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Connexion aux astres...';
                btn.disabled = true;

                try {
                    const res = await fetch('/api/loto/astro', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(payload)
                    });
                    if(!res.ok) throw new Error("Erreur serveur");
                    const data = await res.json();
                    afficherResultatAstro(data);
                } catch(err) {
                    console.error(err);
                    alert("Impossible de calculer le profil astral.");
                } finally {
                    btn.innerHTML = originalText;
                    btn.disabled = false;
                }
            });
        }

        if(btnCopyAstro) {
            btnCopyAstro.addEventListener('click', () => {
                const nums = Array.from(document.querySelectorAll('#astroBalls .badge.bg-primary')).map(el => el.textContent);
                const inputs = document.querySelectorAll('.sim-input');
                inputs.forEach((inp, i) => { if(nums[i]) inp.value = nums[i]; });
                const modalEl = document.getElementById('modalAstro');
                const modal = bootstrap.Modal.getInstance(modalEl);
                modal.hide();
                document.getElementById('formSimulateur').scrollIntoView({behavior:'smooth'});
            });
        }

        // 8. Onglets (Chart vs Heatmap)
        const tabs = document.querySelectorAll('#graphTabs button');
        const searchContainerMain = document.getElementById('searchContainerMain');
        const searchContainerChance = document.getElementById('searchContainerChance');
        const chartCanvas = document.getElementById('scatterChart');
        const heatContainer = document.getElementById('heatmapContainer');

        tabs.forEach(tab => {
            tab.addEventListener('click', (e) => {
                tabs.forEach(t => t.classList.remove('active'));
                e.target.classList.add('active');
                activeTab = e.target.getAttribute('data-target');

                searchContainerMain.classList.add('d-none');
                searchContainerChance.classList.add('d-none');
                chartCanvas.classList.add('d-none');
                if(heatContainer) heatContainer.classList.add('d-none');

                if (activeTab === 'main') {
                    searchContainerMain.classList.remove('d-none');
                    chartCanvas.classList.remove('d-none');
                } else if (activeTab === 'chance') {
                    searchContainerChance.classList.remove('d-none');
                    chartCanvas.classList.remove('d-none');
                } else if (activeTab === 'heat') {
                    if(heatContainer) heatContainer.classList.remove('d-none');
                }
                filtrerEtAfficher();
            });
        });

        // 9. Recherche
        const searchInput = document.getElementById('searchNum');
        const searchChanceInput = document.getElementById('searchChance');
        const resetButtons = document.querySelectorAll('.btn-reset');

        if (searchInput) searchInput.addEventListener('input', filtrerEtAfficher);
        if (searchChanceInput) searchChanceInput.addEventListener('input', filtrerEtAfficher);

        resetButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                searchInput.value = '';
                searchChanceInput.value = '';
                filtrerEtAfficher();
            });
        });
    }

    // --- FONCTIONNALITÉS ---

    function afficherResultatAstro(data) {
        const resDiv = document.getElementById('astroResult');
        resDiv.classList.remove('d-none');
        document.getElementById('astroTitle').textContent = `Profil ${data.signeSolaire}`;
        document.getElementById('astroPath').textContent = data.cheminDeVie;
        document.getElementById('astroElement').textContent = data.element;
        document.getElementById('astroPhrase').textContent = `"${data.phraseHoroscope}"`;

        const ballsDiv = document.getElementById('astroBalls');
        ballsDiv.innerHTML = '';
        data.luckyNumbers.forEach(n => {
            const b = document.createElement('span');
            b.className = 'badge rounded-pill bg-primary fs-6 py-2 px-3 shadow-sm';
            b.textContent = n;
            ballsDiv.appendChild(b);
        });
        const c = document.createElement('span');
        c.className = 'badge rounded-circle bg-danger fs-6 d-flex align-items-center justify-content-center shadow-sm';
        c.style.width = '35px'; c.style.height = '35px';
        c.textContent = data.luckyChance;
        ballsDiv.appendChild(c);
    }

    async function analyserGrille(e) {
        e.preventDefault();
        const inputs = document.querySelectorAll('.sim-input');
        const boules = Array.from(inputs).map(i => i.value ? parseInt(i.value) : null).filter(val => val !== null && !isNaN(val));
        const dateSimu = document.getElementById('simDate').value;

        if (!dateSimu) { alert("Veuillez choisir une date."); return; }
        if (boules.length < 2) { alert("Veuillez entrer au moins 2 numéros."); return; }
        if (new Set(boules).size !== boules.length) { alert("Doublons interdits."); return; }

        const payload = { boules: boules, date: dateSimu };
        try {
            const res = await fetch('/api/loto/simuler', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if(!res.ok) throw new Error();
            const data = await res.json();
            afficherResultatsSimulation(data, boules.length);
        } catch (err) { console.error(err); alert("Erreur analyse."); }
    }

    function afficherResultatsSimulation(data, nbBoulesJouees) {
        const container = document.getElementById('simResult');
        container.classList.remove('d-none');

        // Construction dynamique des onglets
        let tabsHtml = '';
        let contentHtml = '';
        const startActive = nbBoulesJouees;

        [5, 4, 3, 2].forEach(size => {
            if (size <= nbBoulesJouees) {
                const isActive = (size === startActive);
                const count = (size === 5 ? data.quintuplets : size === 4 ? data.quartets : size === 3 ? data.trios : data.pairs).length;
                tabsHtml += `<li class="nav-item"><button class="nav-link ${isActive ? 'active' : ''}" data-bs-toggle="pill" data-bs-target="#pills-${size}">${size} Numéros (${count})</button></li>`;
                contentHtml += generateTabPane(`pills-${size}`, (size === 5 ? data.quintuplets : size === 4 ? data.quartets : size === 3 ? data.trios : data.pairs), isActive);
            }
        });

        container.innerHTML = `
            <div class="alert alert-primary bg-primary bg-opacity-10 border-primary border-opacity-25 text-primary text-center py-2 mb-3">
                <i class="bi bi-info-circle-fill me-2"></i>Analyse de <strong>${nbBoulesJouees} numéros</strong> pour un <strong>${data.jourSimule}</strong>
            </div>
            <ul class="nav nav-pills mb-3 justify-content-center" id="pills-tab">${tabsHtml}</ul>
            <div class="tab-content">${contentHtml}</div>
        `;
    }

    function generateTabPane(id, items, isActive) {
        let content = '';
        if (!items || items.length === 0) content = '<p class="text-muted text-center py-3 small">Aucune combinaison historique.</p>';
        else {
            items.sort((a, b) => b.ratio - a.ratio);
            content = '<div class="list-group">';
            items.forEach(item => {
                let datesStr = item.dates.slice(0, 5).join(', ');
                if (item.dates.length > 5) datesStr += ` ... (+${item.dates.length - 5})`;
                let barColor = item.ratio > 1.5 ? 'bg-danger' : item.ratio > 1.1 ? 'bg-warning' : item.ratio < 0.6 ? 'bg-info' : 'bg-success';
                let width = Math.min(item.ratio * 50, 100);

                content += `
                    <div class="list-group-item">
                        <div class="d-flex justify-content-between align-items-center mb-1">
                            <span class="fw-bold text-primary">${item.numeros.join(' - ')}</span>
                            <span class="badge bg-light text-dark border">${item.dates.length} sorties</span>
                        </div>
                        <div class="d-flex align-items-center mb-1" style="height: 6px;">
                            <div class="progress w-100" style="height: 6px;">
                                <div class="progress-bar ${barColor}" style="width: ${width}%"></div>
                            </div>
                        </div>
                        <div class="d-flex justify-content-between small text-muted">
                            <span>Ratio: <strong>${item.ratio}</strong></span>
                            <span class="text-truncate" style="max-width: 200px;">${datesStr}</span>
                        </div>
                    </div>`;
            });
            content += '</div>';
        }
        return `<div class="tab-pane fade ${isActive ? 'show active' : ''}" id="${id}">${content}</div>`;
    }

    async function genererPronostic() {
        const dateInput = document.getElementById('simDate');
        const countInput = document.getElementById('pronoCount');
        if (!dateInput.value) dateInput.valueAsDate = new Date();

        const btn = document.getElementById('btnMagic');
        const originalText = btn.innerHTML;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>';
        btn.disabled = true;

        try {
            const res = await fetch(`/api/loto/generate?date=${dateInput.value}&count=${countInput.value}`);
            const data = await res.json();
            afficherMultiplesPronostics(data, dateInput.value);
        } catch (e) { alert("Erreur pronostics."); } finally {
            btn.innerHTML = originalText;
            btn.disabled = false;
        }
    }

    function afficherMultiplesPronostics(list, dateStr) {
        const container = document.getElementById('pronoResult');
        const listContainer = document.getElementById('pronoList');
        container.classList.remove('d-none');
        document.getElementById('pronoDate').textContent = new Date(dateStr).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
        listContainer.innerHTML = '';

        list.forEach((grid, index) => {
            const pctDuo = Math.min(grid.maxRatioDuo * 50, 100);
            const colorDuo = grid.maxRatioDuo > 1.2 ? 'bg-danger' : 'bg-success';

            const col = document.createElement('div');
            col.className = 'col-md-6 col-lg-4';
            col.innerHTML = `
                <div class="card h-100 shadow-sm border-${grid.dejaSortie ? 'danger' : 'light'}">
                    <div class="card-body p-3">
                        <div class="d-flex justify-content-between align-items-center mb-3">
                            <span class="badge bg-secondary">Grille #${index + 1}</span>
                            ${grid.dejaSortie ? '<span class="badge bg-danger">⚠️ DÉJÀ SORTIE</span>' : '<span class="badge bg-light text-success border border-success">✨ Inédite</span>'}
                        </div>
                        <div class="d-flex justify-content-center gap-1 mb-3">
                            ${grid.boules.map(b => `<span class="badge rounded-circle bg-primary fs-6 d-flex align-items-center justify-content-center" style="width:32px; height:32px;">${b}</span>`).join('')}
                            <span class="badge rounded-circle bg-danger fs-6 d-flex align-items-center justify-content-center" style="width:32px; height:32px;">${grid.numeroChance}</span>
                        </div>
                        <div class="small text-muted mb-3">
                            <div class="d-flex justify-content-between mb-1"><span>Force Duo</span><span class="fw-bold">${grid.maxRatioDuo}x</span></div>
                            <div class="progress" style="height: 4px;"><div class="progress-bar ${colorDuo}" style="width: ${pctDuo}%"></div></div>
                        </div>
                        <div class="d-flex gap-2">
                            <button class="btn btn-outline-dark btn-sm flex-grow-1 btn-copier" data-nums="${grid.boules.join(',')}" data-chance="${grid.numeroChance}">
                                <i class="bi bi-eye"></i> Analyser
                            </button>
                            <a href="https://www.fdj.fr/jeux-de-tirage/loto" target="_blank" class="btn btn-primary btn-sm flex-grow-1 fw-bold">
                                <i class="bi bi-ticket-fill"></i> Jouer
                            </a>
                        </div>
                    </div>
                </div>`;
            listContainer.appendChild(col);
        });

        document.querySelectorAll('.btn-copier').forEach(btn => {
            btn.addEventListener('click', (e) => {
                const nums = e.target.getAttribute('data-nums').split(',');
                const inputs = document.querySelectorAll('.sim-input');
                inputs.forEach((inp, i) => { if(nums[i]) inp.value = nums[i]; });
                document.getElementById('formSimulateur').scrollIntoView({behavior: 'smooth'});
            });
        });
    }

    // --- IMPORT & MANUEL ---

    async function ajouterTirageManuel(e) {
        e.preventDefault();
        const boules = [1,2,3,4,5].map(i => parseInt(document.getElementById('mB'+i).value));
        const chance = parseInt(document.getElementById('mChance').value);
        const date = document.getElementById('mDate').value;
        if(!date || isNaN(chance)) return alert("Champs invalides");
        try {
            await fetch('/api/loto/add', {
                method:'POST', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({dateTirage:date, boule1:boules[0], boule2:boules[1], boule3:boules[2], boule4:boules[3], boule5:boules[4], numeroChance:chance})
            });
            alert('Ajouté !'); document.getElementById('formManuel').reset(); chargerStats();
        } catch(err) { console.error(err); }
    }

    async function uploadCsv() {
        const fileInput = document.getElementById('csvFile');
        if (!fileInput.files[0]) return alert("Fichier requis");
        const btn = document.getElementById('btnImport');
        btn.disabled = true; btn.innerText = 'Importation...';
        const formData = new FormData(); formData.append('file', fileInput.files[0]);
        try {
            const res = await fetch('/api/loto/import', { method:'POST', body: formData });
            if(res.ok) { alert('Import réussi !'); chargerStats(); } else alert('Erreur import');
        } catch(e) { console.error(e); } finally { btn.disabled = false; btn.innerText='Importer'; }
    }

    // --- DATA FETCH & CHARTS ---

    async function chargerStats(jour = '') {
        let url = '/api/loto/stats';
        if (jour) url += `?jour=${jour}`;
        try {
            const res = await fetch(url);
            const response = await res.json();
            rawData = response.points;
            if(document.getElementById('dateMin')) {
                document.getElementById('dateMin').textContent = response.dateMin;
                document.getElementById('dateMax').textContent = response.dateMax;
                document.getElementById('nbTirages').textContent = response.nombreTirages;
                document.getElementById('infoPeriode').style.display = 'flex';
            }
            filtrerEtAfficher();
        } catch (e) { console.error(e); }
    }

    function filtrerEtAfficher() {
        const termMain = document.getElementById('searchNum').value;
        const termChance = document.getElementById('searchChance').value;
        let mainNumbers = rawData.filter(d => !d.chance);
        let chanceNumbers = rawData.filter(d => d.chance);

        if (termMain.trim()) {
            const nums = parseSearch(termMain);
            if (nums.length > 0) mainNumbers = mainNumbers.filter(d => nums.includes(d.numero));
        }
        if (termChance.trim()) {
            const nums = parseSearch(termChance);
            if (nums.length > 0) chanceNumbers = chanceNumbers.filter(d => nums.includes(d.numero));
        }

        updateDashboard(mainNumbers, chanceNumbers);

        if (activeTab === 'main') updateChart(mainNumbers, false);
        else if (activeTab === 'chance') updateChart(chanceNumbers, true);
        else if (activeTab === 'heat') updateHeatmap(mainNumbers);
    }

    function updateChart(data, isChanceMode) {
        const ctx = document.getElementById('scatterChart').getContext('2d');
        if (currentChart) currentChart.destroy();
        const activeBtn = document.querySelector('.btn-filter.active');
        const jourLabel = activeBtn ? activeBtn.getAttribute('data-jour') : '';
        let pointColor = isChanceMode ? 'rgba(220, 53, 69, 0.7)' : getColorByDay(jourLabel);

        currentChart = new Chart(ctx, {
            type: 'scatter',
            data: {
                datasets: [{
                    label: isChanceMode ? 'Chance' : 'Boules',
                    data: data.map(d => ({ x: jitter(d.ecart), y: jitter(d.frequence), realX: d.ecart, realY: d.frequence, num: d.numero })),
                    backgroundColor: pointColor,
                    borderColor: 'white', borderWidth: 1, pointRadius: 6, pointHoverRadius: 10
                }]
            },
            options: {
                plugins: { tooltip: { callbacks: { label: (ctx) => `N°${ctx.raw.num} : Sorti ${ctx.raw.realY}x (Retard: ${ctx.raw.realX}j)` } }, legend: {display: false} },
                scales: { x: { title: {display: true, text: 'Écart (jours)'}, min: -1 }, y: { title: {display: true, text: 'Fréquence'} } }
            }
        });
    }

    function updateHeatmap(data) {
        const grid = document.getElementById('heatmapGrid');
        if (!grid) return;
        grid.innerHTML = '';
        if (data.length === 0) return;
        const sortedData = [...data].sort((a, b) => a.numero - b.numero);
        const maxFreq = Math.max(...data.map(d => d.frequence));
        const minFreq = Math.min(...data.map(d => d.frequence));

        sortedData.forEach(item => {
            const el = document.createElement('div');
            let ratio = (item.frequence - minFreq) / (maxFreq - minFreq || 1);
            const hue = (1 - ratio) * 240; // 240(Bleu) -> 0(Rouge)
            el.className = 'heatmap-cell';
            el.style.backgroundColor = `hsl(${hue}, 75%, 55%)`;
            el.innerHTML = item.numero;
            el.title = `N°${item.numero} (Freq: ${item.frequence})`;
            grid.appendChild(el);
        });
    }

    function updateDashboard(mainData, chanceData) {
        fillSection('main', mainData);
        fillSection('chance', chanceData);
    }
    function fillSection(prefix, data) {
        const sortedFreq = [...data].sort((a, b) => b.frequence - a.frequence);
        const sortedGap = [...data].sort((a, b) => b.ecart - a.ecart);
        fillList(`${prefix}TopFreq`, sortedFreq.slice(0, 5), 'sorties');
        fillList(`${prefix}TopGap`, sortedGap.slice(0, 5), 'jours', true);
    }
    function fillList(id, items, suffix, isGap) {
        const list = document.getElementById(id);
        if(!list) return; list.innerHTML = '';
        items.forEach(item => {
            const val = isGap ? item.ecart : item.frequence;
            const li = document.createElement('li');
            li.className = 'list-group-item d-flex justify-content-between px-0 py-1 border-0 bg-transparent';
            li.innerHTML = `<span class="fw-bold">N° ${item.numero}</span><span class="badge bg-secondary bg-opacity-25 text-dark rounded-pill">${val} ${suffix}</span>`;
            list.appendChild(li);
        });
    }

    function jitter(val) { return val + (Math.random() - 0.5) * 0.7; }
    function parseSearch(str) { return str.split(/[\s,]+/).map(s => parseInt(s)).filter(n => !isNaN(n)); }
    function getColorByDay(j) {
        const c = { 'MONDAY': '#ff6384', 'WEDNESDAY': '#4bc0c0', 'SATURDAY': '#36a2eb' };
        return c[j] || '#36a2eb';
    }
});