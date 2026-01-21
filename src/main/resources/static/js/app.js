document.addEventListener('DOMContentLoaded', () => {

    // --- Variables Globales ---
    let currentChart = null;
    let radarChart = null;
    let rawData = [];
    let activeTab = 'main';

    // Variables pour la sélection multiple
    let selectedGrids = new Set();
    let currentGridsData = [];

    // --- FONCTIONS GLOBALES (Accessibles depuis le HTML) ---

    // 1. Initialisation des données depuis le HTML (Thymeleaf)
    window.setCurrentGridsData = function(data) {
        currentGridsData = data;
        selectedGrids.clear();
        updateBulkBar();
    };

    // 2. Tout sélectionner / Désélectionner
    window.toggleAllGrids = function() {
        if (!currentGridsData || currentGridsData.length === 0) return;

        const allSelected = (selectedGrids.size === currentGridsData.length);

        if (allSelected) {
            // Tout désélectionner
            currentGridsData.forEach((_, index) => {
                selectedGrids.delete(index);
                updateCardStyle(index, false);
            });
        } else {
            // Tout sélectionner
            currentGridsData.forEach((_, index) => {
                selectedGrids.add(index);
                updateCardStyle(index, true);
            });
        }
        updateBulkBar();
    };

    // 3. Sélectionner une grille unique (Click sur la carte)
    window.toggleGridSelection = function(index, cardElement) {
        if (selectedGrids.has(index)) {
            selectedGrids.delete(index);
            updateCardStyle(index, false);
        } else {
            selectedGrids.add(index);
            updateCardStyle(index, true);
        }
        updateBulkBar();
    };

    // Helper de style
    function updateCardStyle(index, isSelected) {
        const card = document.getElementById(`grid-card-${index}`);
        const checkbox = document.getElementById(`check-${index}`);
        if(!card || !checkbox) return;

        if(isSelected) {
            card.classList.remove('border-light');
            card.classList.add('border-primary', 'bg-primary', 'bg-opacity-10');
            checkbox.checked = true;
        } else {
            card.classList.remove('border-primary', 'bg-primary', 'bg-opacity-10');
            card.classList.add('border-light');
            checkbox.checked = false;
        }
    }

    // Mise à jour de la barre du bas
    function updateBulkBar() {
        const bar = document.getElementById('bulkActionBar');
        const count = selectedGrids.size;
        if (count > 0) {
            bar.classList.remove('d-none');
            document.getElementById('bulkCountBadge').textContent = count;
            document.getElementById('bulkTotalCost').textContent = (count * 2.20).toFixed(2);
        } else {
            bar.classList.add('d-none');
        }
    }

    // --- INITIALISATION AU CHARGEMENT ---
    setupEventListeners();

    // Initialisation des Popovers Bootstrap
    var popoverTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="popover"]'));
    var popoverList = popoverTriggerList.map(function (popoverTriggerEl) {
        return new bootstrap.Popover(popoverTriggerEl)
    });

    chargerStats();
    chargerFavoris();
    checkWinEffect();

    // Bouton Sauvegarder Favori (Simulateur)
    const btnSaveFav = document.getElementById('btnSaveFav');
    if(btnSaveFav) {
        btnSaveFav.addEventListener('click', () => {
            const inputs = document.querySelectorAll('.sim-input');
            const nums = Array.from(inputs).map(i => i.value).filter(v => v);

            if(nums.length < 5) return alert("Saisissez 5 numéros !");

            let favoris = JSON.parse(localStorage.getItem('mesFavorisLoto') || "[]");
            favoris.push(nums);
            localStorage.setItem('mesFavorisLoto', JSON.stringify(favoris));

            chargerFavoris();
            confetti({ particleCount: 30, spread: 50, origin: { y: 0.6 } });
        });
    }

    // --- GESTIONNAIRES D'ÉVÉNEMENTS ---
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

        // 5. Algorithme Hybride
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

                    // Mise à jour des données globales pour la sélection
                    currentGridsData = data;
                    selectedGrids.clear();
                    updateBulkBar();

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

        // 7. Astro Simple & Copy
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
                        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
                    });
                    if(!res.ok) throw new Error("Erreur serveur");
                    const data = await res.json();
                    afficherResultatAstro(data);
                } catch(err) { console.error(err); alert("Erreur Astro."); } finally { btn.innerHTML = originalText; btn.disabled = false; }
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

        // 8. Onglets Graphiques
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

        // 9. Recherche & Reset
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

        const statsCollapse = document.getElementById('collapseStats');
        if (statsCollapse) {
            statsCollapse.addEventListener('shown.bs.collapse', function () {
                if (currentChart) currentChart.resize();
            });
        }

        // 10. GESTION DU "TOUT JOUER" (BULK) - MODALES & CONFIRMATION

        // Bouton Ouvrir Modal
        const btnOpenBulk = document.getElementById('btnOpenBulkModal');
        if(btnOpenBulk) {
            btnOpenBulk.addEventListener('click', () => {
                const count = selectedGrids.size;
                const dateSimu = document.getElementById('simDate').value;
                document.getElementById('modalBulkCount').textContent = count;
                document.getElementById('bulkCostInput').value = (count * 2.20).toFixed(2);

                // Init flatpickr sur le champ date de la modal
                const fp = flatpickr("#bulkDateInput", { "locale": "fr", "dateFormat": "Y-m-d", "altInput": true, "altFormat": "j F Y" });
                fp.setDate(dateSimu, true);

                const modal = new bootstrap.Modal(document.getElementById('modalBulkBet'));
                modal.show();
            });
        }

        // Bouton Confirmer Bulk
        const btnConfirmBulk = document.getElementById('btnConfirmBulk');
        if(btnConfirmBulk) {
            btnConfirmBulk.addEventListener('click', async () => {
                const btn = btnConfirmBulk;
                const originalText = btn.innerHTML;
                const dateJeu = document.getElementById('bulkDateInput').value;

                if (!dateJeu) { alert("Veuillez choisir une date."); return; }

                const gridsToSend = [];
                selectedGrids.forEach(index => {
                    const g = currentGridsData[index];
                    // Gestion sécurisée du nom du champ chance (DTO vs Objet local)
                    const chance = g.numeroChance !== undefined ? g.numeroChance : g.chance;
                    const line = [...g.boules, chance];
                    gridsToSend.push(line);
                });

                const payload = { dateJeu: dateJeu, grilles: gridsToSend };

                btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Envoi...';
                btn.disabled = true;

                try {
                    const res = await fetch('/bets/add-bulk', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload)
                    });

                    if (res.ok) {
                        bootstrap.Modal.getInstance(document.getElementById('modalBulkBet')).hide();
                        confetti({ particleCount: 100, spread: 70, origin: { y: 0.6 } });
                        setTimeout(() => window.location.reload(), 1500);
                    } else {
                        throw new Error("Erreur serveur lors de la sauvegarde.");
                    }
                } catch(e) {
                    alert("Erreur : " + e.message);
                } finally {
                    btn.innerHTML = originalText;
                    btn.disabled = false;
                }
            });
        }
    }

    // --- GÉNÉRATION PRONOSTICS (AJAX) ---

    async function genererPronostic() {
        const dateInput = document.getElementById('simDate');
        const countInput = document.getElementById('pronoCount');
        const resultDiv = document.getElementById('pronoResult');
        const listDiv = document.getElementById('pronoList');

        if (!dateInput.value) dateInput.valueAsDate = new Date();

        const btn = document.getElementById('btnMagic');
        const originalText = btn.innerHTML;

        // UI Loading
        btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Calcul...';
        btn.disabled = true;

        resultDiv.classList.remove('d-none');
        listDiv.innerHTML = '<div class="text-center py-3"><div class="spinner-border text-primary"></div><div class="small mt-2 text-muted">L\'IA optimise les combinaisons...</div></div>';

        try {
            // Appel API
            const res = await fetch(`/api/loto/generate?date=${dateInput.value}&count=${countInput.value}`);
            if(!res.ok) throw new Error("Erreur API");

            const data = await res.json();

            // Mise à jour des données globales pour la sélection
            currentGridsData = data;
            selectedGrids.clear();
            updateBulkBar();

            // Affichage
            afficherMultiplesPronostics(data, dateInput.value);

        } catch (e) {
            console.error(e);
            listDiv.innerHTML = '<div class="alert alert-danger py-2 small"><i class="bi bi-exclamation-triangle me-2"></i>Erreur lors de la génération.</div>';
        } finally {
            btn.innerHTML = originalText;
            btn.disabled = false;
        }
    }

    // --- AFFICHAGE DES CARTES (Design Modernisé) ---

    function afficherMultiplesPronostics(list, dateStr) {
        const container = document.getElementById('pronoResult');
        const listContainer = document.getElementById('pronoList');
        const dateLabel = document.getElementById('pronoDate');

        // 1. Affichage du container et mise à jour de la date
        container.classList.remove('d-none');
        if(dateLabel) {
            const d = new Date(dateStr);
            dateLabel.textContent = d.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
        }

        // 2. CORRECTION IMPORTANTE : Recréation du bouton "Tout"
        // On cible le conteneur de boutons dans le header de la carte
        const headerBtnGroup = container.querySelector('.d-flex.justify-content-between .btn-group');
        if(headerBtnGroup) {
            headerBtnGroup.innerHTML = `
            <button class="btn btn-sm btn-outline-secondary" onclick="toggleAllGrids()">
                <i class="bi bi-check-all"></i> Tout
            </button>`;
        }

        // 3. Nettoyage de la liste précédente
        listContainer.innerHTML = '';

        // 4. Génération des nouvelles cartes
        list.forEach((bet, index) => {
            // Gestion des Badges Algo
            let badgeHtml = '';
            const type = bet.typeAlgo || '';
            if (type.includes('GENETIQUE') || type.includes('OPTIMAL'))
                badgeHtml = '<span class="badge bg-primary bg-opacity-10 text-primary border border-primary"><i class="bi bi-cpu-fill"></i> IA Optimal</span>';
            else if (type.includes('FLEXIBLE'))
                badgeHtml = '<span class="badge bg-warning bg-opacity-10 text-dark border border-warning"><i class="bi bi-exclamation-triangle"></i> IA Flexible</span>';
            else
                badgeHtml = '<span class="badge bg-secondary bg-opacity-10 text-dark border"><i class="bi bi-dice-5-fill"></i> Hasard</span>';

            // Gestion sécurisée du numéro chance (compatible avec les différents noms de champs DTO)
            const chanceNum = bet.numeroChance !== undefined ? bet.numeroChance : bet.chance;

            const col = document.createElement('div');
            col.className = 'col-md-6 col-lg-4 animate-up';
            col.style.animationDelay = (index * 0.05) + 's';

            col.innerHTML = `
            <div class="card h-100 shadow-sm border-light grid-card cursor-pointer position-relative" 
                 id="grid-card-${index}"
                 onclick="toggleGridSelection(${index}, this)">
                
                <div class="position-absolute top-0 end-0 p-2">
                    <input type="checkbox" class="form-check-input fs-5" id="check-${index}" style="pointer-events: none;">
                </div>

                <div class="card-body p-3">
                    <div class="d-flex align-items-center mb-2 gap-2">
                        <span class="badge bg-light text-muted border">#${index + 1}</span>
                        ${badgeHtml}
                    </div>
                    
                    <div class="d-flex justify-content-center gap-1 mb-3">
                        ${bet.boules.map(b => `<span class="badge rounded-circle bg-dark fs-6 d-flex align-items-center justify-content-center shadow-sm" style="width:32px; height:32px;">${b}</span>`).join('')}
                        <span class="badge rounded-circle bg-danger fs-6 d-flex align-items-center justify-content-center shadow-sm" style="width:32px; height:32px;">${chanceNum}</span>
                    </div>
                    
                    <div class="small text-muted bg-light p-2 rounded mb-0 d-flex justify-content-between align-items-center">
                        <div><i class="bi bi-graph-up me-1"></i>Score: <strong>${bet.scoreGlobal}</strong></div>
                        <button class="btn btn-xs btn-link text-decoration-none p-0" 
                                onclick="event.stopPropagation(); preparerGrille(${bet.boules[0]}, ${bet.boules[1]}, ${bet.boules[2]}, ${bet.boules[3]}, ${bet.boules[4]}, ${chanceNum})"
                                title="Jouer cette grille seule">
                            Jouer seul
                        </button>
                    </div>
                </div>
            </div>`;

            listContainer.appendChild(col);
        });
    }

    // --- AUTRES FONCTIONS (Astro, Simulation, Charts...) ---

    function chargerFavoris() {
        const container = document.getElementById('favList');
        if(!container) return;
        container.innerHTML = '';
        let favoris = JSON.parse(localStorage.getItem('mesFavorisLoto') || "[]");
        favoris.forEach((nums, index) => {
            const badge = document.createElement('span');
            badge.className = "badge bg-white text-dark border cursor-pointer p-2 shadow-sm";
            badge.innerHTML = `<i class="bi bi-star-fill text-warning me-1"></i> ${nums.join('-')}`;
            badge.onclick = () => {
                const inputs = document.querySelectorAll('.sim-input');
                nums.forEach((n, i) => { if(inputs[i]) inputs[i].value = n; });
            };
            badge.ondblclick = () => {
                favoris.splice(index, 1);
                localStorage.setItem('mesFavorisLoto', JSON.stringify(favoris));
                chargerFavoris();
            };
            container.appendChild(badge);
        });
    }

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
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
            });
            if(!res.ok) throw new Error();
            const data = await res.json();
            afficherResultatsSimulation(data, boules.length);
        } catch (err) { console.error(err); alert("Erreur analyse."); }
    }

    function afficherResultatsSimulation(data, nbBoulesJouees) {
        const container = document.getElementById('simResult');
        container.classList.remove('d-none');

        const inputs = document.querySelectorAll('.sim-input');
        const numerosJoues = Array.from(inputs).map(i => i.value ? parseInt(i.value) : 0).filter(n => n > 0);
        let somme = numerosJoues.reduce((a, b) => a + b, 0);
        let pairs = numerosJoues.filter(n => n % 2 === 0).length;
        let impairs = numerosJoues.length - pairs;
        let maxRatio = 0;
        if (data.quintuplets && data.quintuplets.length > 0) maxRatio = data.quintuplets[0].ratio;
        else if (data.quartets && data.quartets.length > 0) maxRatio = data.quartets[0].ratio;
        let freqPourcent = maxRatio > 0 ? (maxRatio).toFixed(2) + 'x' : '-';
        let tabsHtml = '';
        let contentHtml = '';
        const startActive = nbBoulesJouees;
        [5, 4, 3, 2].forEach(size => {
            if (size <= nbBoulesJouees) {
                const isActive = (size === startActive);
                const items = (size === 5 ? data.quintuplets : size === 4 ? data.quartets : size === 3 ? data.trios : data.pairs);
                const count = items ? items.length : 0;
                tabsHtml += `
                    <li class="nav-item" role="presentation">
                        <button class="nav-link ${isActive ? 'active' : ''}" id="pills-${size}-tab" data-bs-toggle="pill" data-bs-target="#pills-${size}" type="button">
                            ${size} Num <span class="badge bg-white text-primary ms-1 shadow-sm">${count}</span>
                        </button>
                    </li>`;
                contentHtml += generateTabPane(`pills-${size}`, items, isActive);
            }
        });
        container.innerHTML = `
            <div class="card border-0 shadow-sm animate-up mt-3">
                <div class="card-header bg-white border-bottom-0 py-3 d-flex justify-content-between align-items-center">
                    <h6 class="fw-bold text-primary mb-0"><i class="bi bi-bar-chart-fill me-2"></i>Analyse de votre grille</h6>
                    <button class="btn btn-sm btn-outline-secondary" onclick="document.getElementById('simResult').classList.add('d-none')">Fermer</button>
                </div>
                <div class="card-body bg-light">
                    <div class="row g-3 mb-4 text-center">
                        <div class="col-4">
                            <div class="bg-white p-2 rounded shadow-sm border h-100 d-flex flex-column justify-content-center">
                                <small class="text-muted d-block text-uppercase fw-bold" style="font-size:0.65rem;">Somme</small>
                                <span class="fw-bold text-dark fs-5">${somme}</span>
                            </div>
                        </div>
                        <div class="col-4">
                            <div class="bg-white p-2 rounded shadow-sm border h-100 d-flex flex-column justify-content-center">
                                <small class="text-muted d-block text-uppercase fw-bold" style="font-size:0.65rem;">Parité</small>
                                <span class="fw-bold text-primary fs-6">${pairs} Pair / ${impairs} Imp</span>
                            </div>
                        </div>
                        <div class="col-4">
                            <div class="bg-white p-2 rounded shadow-sm border h-100 d-flex flex-column justify-content-center">
                                <small class="text-muted d-block text-uppercase fw-bold" style="font-size:0.65rem;">Perf. Max</small>
                                <span class="fw-bold text-success fs-6">${freqPourcent}</span>
                            </div>
                        </div>
                    </div>
                    <div class="alert alert-primary bg-primary bg-opacity-10 border-primary border-opacity-25 text-primary text-center py-2 mb-3 small">
                        <i class="bi bi-info-circle-fill me-1"></i>
                        Historique des sorties pour un <strong>${data.jourSimule}</strong>
                    </div>
                    <ul class="nav nav-pills mb-3 justify-content-center" id="pills-tab">${tabsHtml}</ul>
                    <div class="tab-content" id="pills-tabContent">${contentHtml}</div>
                </div>
                <div class="card-footer bg-white border-top-0 text-center py-2">
                    <small class="text-muted fst-italic" style="font-size: 0.75rem;">Conseil : Une somme entre 120 et 180 est statistiquement la plus fréquente.</small>
                </div>
            </div>`;
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

    // --- CHARTS UTILS ---
    function jitter(val) { return val + (Math.random() - 0.5) * 0.7; }
    function parseSearch(str) { return str.split(/[\s,]+/).map(s => parseInt(s)).filter(n => !isNaN(n)); }
    function getColorByDay(j) {
        const c = { 'MONDAY': '#ff6384', 'WEDNESDAY': '#4bc0c0', 'SATURDAY': '#36a2eb' };
        return c[j] || '#36a2eb';
    }

    function checkWinEffect() {
        const urlParams = new URLSearchParams(window.location.search);
        if (urlParams.has('win')) {
            lancerConfettis();
            const toastEl = document.getElementById('winToast');
            if(toastEl) {
                const toast = new bootstrap.Toast(toastEl);
                toast.show();
            }
            const newUrl = window.location.pathname;
            window.history.replaceState({}, document.title, newUrl);
        }
    }

    function lancerConfettis() {
        var duration = 3 * 1000;
        var animationEnd = Date.now() + duration;
        var defaults = { startVelocity: 30, spread: 360, ticks: 60, zIndex: 10000 };
        function randomInRange(min, max) { return Math.random() * (max - min) + min; }
        var interval = setInterval(function() {
            var timeLeft = animationEnd - Date.now();
            if (timeLeft <= 0) return clearInterval(interval);
            var particleCount = 50 * (timeLeft / duration);
            confetti(Object.assign({}, defaults, { particleCount, origin: { x: randomInRange(0.1, 0.3), y: Math.random() - 0.2 } }));
            confetti(Object.assign({}, defaults, { particleCount, origin: { x: randomInRange(0.7, 0.9), y: Math.random() - 0.2 } }));
        }, 250);
    }

    function updateRadar(data) {
        const recentData = data.slice(0, 50);
        let totalPairs = 0;
        let totalHigh = 0;
        let totalSum = 0;
        recentData.forEach(d => {
            if (d.numero % 2 === 0) totalPairs += d.frequence;
            if (d.numero > 25) totalHigh += d.frequence;
            totalSum += (d.numero * d.frequence);
        });
        const totalSorties = recentData.reduce((acc, val) => acc + val.frequence, 0);
        const pctPair = Math.round((totalPairs / totalSorties) * 100);
        const pctHigh = Math.round((totalHigh / totalSorties) * 100);
        const ctx = document.getElementById('radarChart').getContext('2d');
        if (radarChart) radarChart.destroy();
        radarChart = new Chart(ctx, {
            type: 'radar',
            data: {
                labels: ['Parité (Pairs)', 'Taille (>25)', 'Zone Chaude', 'Finales 0-4', 'Finales 5-9'],
                datasets: [{
                    label: 'Tendance Actuelle',
                    data: [pctPair, pctHigh, 60, 55, 45],
                    fill: true, backgroundColor: 'rgba(79, 70, 229, 0.2)', borderColor: '#4F46E5', pointBackgroundColor: '#4F46E5', pointBorderColor: '#fff', pointHoverBackgroundColor: '#fff', pointHoverBorderColor: '#4F46E5'
                }, {
                    label: 'Équilibre Théorique', data: [50, 50, 50, 50, 50], fill: true, backgroundColor: 'rgba(200, 200, 200, 0.1)', borderColor: 'rgba(200, 200, 200, 0.5)', pointRadius: 0
                }]
            },
            options: {
                elements: { line: { tension: 0.3 } },
                scales: { r: { angleLines: { display: true, color: '#eee' }, suggestedMin: 30, suggestedMax: 70, pointLabels: { font: { size: 12, family: "'Poppins', sans-serif" } } } }
            }
        });
        const conseilDiv = document.getElementById('radarAnalysis');
        if(conseilDiv) {
            let analyse = [];
            if (pctPair > 55) analyse.push("Les <strong>Pairs</strong> dominent");
            if (pctPair < 45) analyse.push("Les <strong>Impairs</strong> dominent");
            if (pctHigh > 55) analyse.push("Les <strong>Gros numéros</strong> (>25) sont fréquents");
            if (pctHigh < 45) analyse.push("Les <strong>Petits numéros</strong> (≤25) sont fréquents");
            if (analyse.length === 0) conseilDiv.innerHTML = '<span class="text-success"><i class="bi bi-check-circle me-1"></i> Équilibre parfait. Le hasard fait bien les choses.</span>';
            else conseilDiv.innerHTML = '<span class="text-primary"><i class="bi bi-lightbulb me-1"></i> Tendance : ' + analyse.join(" et ") + '.</span>';
        }
    }
});
