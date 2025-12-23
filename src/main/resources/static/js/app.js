document.addEventListener('DOMContentLoaded', () => {

    // --- Variables Globales ---
    let currentChart = null;
    let rawData = [];
    let activeTab = 'main'; // Par défaut : onglet Boules

    // --- Initialisation ---
    setupEventListeners();
    chargerStats();

    // --- Gestionnaires d'événements ---
    function setupEventListeners() {

        // Import
        const btnImport = document.getElementById('btnImport');
        if (btnImport) btnImport.addEventListener('click', uploadCsv);

        // Bouton Magique Pronostic
        const btnMagic = document.getElementById('btnMagic');
        if (btnMagic) btnMagic.addEventListener('click', genererPronostic);

        // Bouton Copier
        document.getElementById('btnCopyProno')?.addEventListener('click', copierPronoVersSimu);

        // Nouveau listener pour le simulateur
        const formSimu = document.getElementById('formSimulateur');
        if (formSimu) formSimu.addEventListener('submit', analyserGrille);

        // Filtres Jours
        document.querySelectorAll('.btn-filter').forEach(btn => {
            btn.addEventListener('click', (e) => {
                document.querySelectorAll('.btn-filter').forEach(b => b.classList.remove('active'));
                const target = e.target.closest('.btn');
                target.classList.add('active');
                chargerStats(target.getAttribute('data-jour'));
            });
        });

        // Formulaire Manuel
        const formManuel = document.getElementById('formManuel');
        if (formManuel) formManuel.addEventListener('submit', ajouterTirageManuel);

        // --- GESTION DES ONGLETS & VISIBILITÉ RECHERCHE ---
        const tabs = document.querySelectorAll('#graphTabs button');
        const searchContainerMain = document.getElementById('searchContainerMain');
        const searchContainerChance = document.getElementById('searchContainerChance');

        tabs.forEach(tab => {
            tab.addEventListener('click', (e) => {
                // 1. Gestion Active Visuelle Onglets
                tabs.forEach(t => t.classList.remove('active'));
                e.target.classList.add('active');

                // 2. Mise à jour variable état
                activeTab = e.target.getAttribute('data-target');

                // 3. Bascule des champs de recherche
                if (activeTab === 'main') {
                    searchContainerMain.classList.remove('d-none');
                    searchContainerChance.classList.add('d-none');
                } else {
                    searchContainerMain.classList.add('d-none');
                    searchContainerChance.classList.remove('d-none');
                }

                // 4. Rafraîchir le graphique
                filtrerEtAfficher();
            });
        });

        // Inputs Recherche
        const searchInput = document.getElementById('searchNum');
        const searchChanceInput = document.getElementById('searchChance');
        const resetButtons = document.querySelectorAll('.btn-reset');

        // Saisie
        if (searchInput) searchInput.addEventListener('input', filtrerEtAfficher);
        if (searchChanceInput) searchChanceInput.addEventListener('input', filtrerEtAfficher);

        // Reset (Fonctionne pour les deux boutons croix)
        resetButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                searchInput.value = '';
                searchChanceInput.value = '';
                filtrerEtAfficher();
            });
        });
    }

    // --- Fonction Modifiée : Analyser Grille ---
        async function analyserGrille(e) {
            e.preventDefault();

            // 1. Récupérer uniquement les champs remplis
            const inputs = document.querySelectorAll('.sim-input');
            const boules = Array.from(inputs)
                .map(input => input.value ? parseInt(input.value) : null)
                .filter(val => val !== null && !isNaN(val));

            const dateSimu = document.getElementById('simDate').value;

            // 2. Validation Souple (Au moins 2 boules)
            if (!dateSimu) { alert("Veuillez choisir une date."); return; }
            if (boules.length < 2) { alert("Veuillez entrer au moins 2 numéros."); return; }

            // Vérification doublons
            if (new Set(boules).size !== boules.length) { alert("Les numéros doivent être distincts."); return; }

            const payload = { boules: boules, date: dateSimu };

            try {
                const res = await fetch('/api/loto/simuler', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                if(!res.ok) throw new Error();
                const data = await res.json();

                // On passe aussi le nombre de boules jouées pour gérer l'affichage
                afficherResultatsSimulation(data, boules.length);

            } catch (err) { console.error(err); alert("Erreur lors de l'analyse."); }
        }

        // --- Fonction Modifiée : Affichage ---
        function afficherResultatsSimulation(data, nbBoulesJouees) {
            const container = document.getElementById('simResult');
            container.classList.remove('d-none');

            // Construction dynamique des onglets selon le nombre de boules jouées
            let tabsHtml = '';
            let contentHtml = '';

            // Helper pour créer un onglet si nécessaire
            const addTab = (size, label, count, isActive) => {
                // On n'affiche pas l'onglet "5 numéros" si on a joué que 3 numéros
                if (size > nbBoulesJouees) return;

                const activeClass = isActive ? 'active' : '';
                tabsHtml += `
                    <li class="nav-item">
                        <button class="nav-link ${activeClass}" data-bs-toggle="pill" data-bs-target="#pills-${size}">
                            ${label} (${count})
                        </button>
                    </li>
                `;
            };

            // On détermine quel est l'onglet le plus élevé à activer par défaut
            const startActive = nbBoulesJouees;

            addTab(5, '🏆 5 Numéros', data.quintuplets.length, startActive === 5);
            addTab(4, '🥇 4 Numéros', data.quartets.length, startActive === 4);
            addTab(3, '🥈 3 Numéros', data.trios.length, startActive === 3);
            addTab(2, '🥉 2 Numéros', data.pairs.length, startActive === 2);

            // Génération du contenu des onglets
            contentHtml += generateTabPane('pills-5', data.quintuplets, startActive === 5);
            contentHtml += generateTabPane('pills-4', data.quartets, startActive === 4);
            contentHtml += generateTabPane('pills-3', data.trios, startActive === 3);
            contentHtml += generateTabPane('pills-2', data.pairs, startActive === 2);

            container.innerHTML = `
                <div class="alert alert-primary text-center py-2 mb-3">
                    Analyse de <strong>${nbBoulesJouees} numéros</strong> pour un <strong>${data.jourSimule}</strong>
                </div>

                <ul class="nav nav-pills mb-3 justify-content-center" id="pills-tab" role="tablist">
                    ${tabsHtml}
                </ul>

                <div class="tab-content" id="pills-tabContent">
                    ${contentHtml}
                </div>
            `;
        }

        // La fonction generateTabPane reste identique à la version précédente
        function generateTabPane(id, items, isActive = false) {
                let content = '';
                if (!items || items.length === 0) {
                    content = '<p class="text-muted text-center py-3">Aucune combinaison de ce type trouvée.</p>';
                } else {
                    // Tri par ratio (les plus "anormaux" en premier)
                    items.sort((a, b) => b.ratio - a.ratio);

                    content = '<div class="list-group">';
                    items.forEach(item => {
                        const dayText = item.sameDayOfWeek ? '📅 Jour identique !' : '';
                        let datesStr = item.dates.slice(0, 5).join(', ');
                        if (item.dates.length > 5) datesStr += ` ... et ${item.dates.length - 5} autres`;

                        // --- LOGIQUE VISUELLE DU RATIO ---
                        let barColor = 'bg-secondary';
                        let barText = 'Normal';
                        let width = Math.min(item.ratio * 50, 100); // Echelle visuelle (1.0 = 50% de la barre)

                        if (item.ratio > 1.5) {
                            barColor = 'bg-danger'; barText = '🔥 Surtirage (Chaud)';
                        } else if (item.ratio > 1.1) {
                            barColor = 'bg-warning text-dark'; barText = '⚡ Fréquent';
                        } else if (item.ratio < 0.6) {
                            barColor = 'bg-info text-dark'; barText = '❄️ Rare (Froid)';
                        } else {
                            barColor = 'bg-success'; barText = '✅ Normal';
                        }

                        content += `
                            <div class="list-group-item">
                                <div class="d-flex w-100 justify-content-between align-items-center mb-2">
                                    <h6 class="mb-0 text-primary fw-bold" style="font-size: 1.1em;">${item.numeros.join(' - ')}</h6>
                                    <span class="badge bg-light text-dark border">${item.dates.length} sorties</span>
                                </div>

                                <div class="d-flex align-items-center mb-2">
                                    <span class="small me-2 text-muted" style="width: 80px;">Indice : ${item.ratio}</span>
                                    <div class="progress flex-grow-1" style="height: 15px;">
                                        <div class="progress-bar ${barColor}" role="progressbar" style="width: ${width}%"
                                             aria-valuenow="${width}" aria-valuemin="0" aria-valuemax="100">
                                            <span class="small ps-1" style="font-size:10px;">${barText}</span>
                                        </div>
                                    </div>
                                </div>

                                <p class="mb-0 small text-muted text-truncate"><i class="bi bi-clock"></i> ${datesStr}</p>
                                ${dayText ? `<small class="text-success fw-bold">${dayText}</small>` : ''}
                            </div>
                        `;
                    });
                    content += '</div>';
                }

                return `
                    <div class="tab-pane fade ${isActive ? 'show active' : ''}" id="${id}" role="tabpanel">
                        ${content}
                    </div>
                `;
            }

    // --- API ---
    // (Cette partie ne change pas, je la compresse pour la lisibilité)
    async function ajouterTirageManuel(e) {
        e.preventDefault();
        // ... (votre code d'ajout manuel existant) ...
        // Pour être sûr, voici la version courte du fetch :
        const boules = [1,2,3,4,5].map(i => parseInt(document.getElementById('mB'+i).value));
        const chance = parseInt(document.getElementById('mChance').value);
        const date = document.getElementById('mDate').value;
        if(!date || isNaN(chance) || boules.some(isNaN)) return alert("Champs invalides");

        try {
            await fetch('/api/loto/add', {
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body:JSON.stringify({dateTirage:date, boule1:boules[0], boule2:boules[1], boule3:boules[2], boule4:boules[3], boule5:boules[4], numeroChance:chance})
            });
            alert('Ajouté !'); document.getElementById('formManuel').reset(); chargerStats();
        } catch(err) { console.error(err); }
    }

    async function uploadCsv() {
        // ... (votre code d'import existant) ...
        const fileInput = document.getElementById('csvFile');
        if (!fileInput.files[0]) return alert("Fichier requis");
        const btn = document.getElementById('btnImport');
        btn.disabled = true; btn.innerText = '...';
        const formData = new FormData(); formData.append('file', fileInput.files[0]);
        try {
            const res = await fetch('/api/loto/import', { method:'POST', body: formData });
            if(res.ok) { alert('OK'); chargerStats(); } else alert('Erreur');
        } catch(e) { console.error(e); } finally { btn.disabled = false; btn.innerText='Importer'; }
    }

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
                document.getElementById('infoPeriode').style.display = 'block';
            }
            filtrerEtAfficher();
        } catch (e) { console.error(e); }
    }

    // --- Coeur du Système ---

    function filtrerEtAfficher() {
        const termMain = document.getElementById('searchNum').value;
        const termChance = document.getElementById('searchChance').value;

        let mainNumbers = rawData.filter(d => !d.chance);
        let chanceNumbers = rawData.filter(d => d.chance);

        // Filtrage des données
        if (termMain.trim()) {
            const nums = parseSearch(termMain);
            if (nums.length > 0) mainNumbers = mainNumbers.filter(d => nums.includes(d.numero));
        }
        if (termChance.trim()) {
            const nums = parseSearch(termChance);
            if (nums.length > 0) chanceNumbers = chanceNumbers.filter(d => nums.includes(d.numero));
        }

        // Mise à jour des Stats textuelles (toujours tout mettre à jour)
        updateDashboard(mainNumbers, chanceNumbers);

        // Mise à jour du Graphique : On affiche SEULEMENT ce qui correspond à l'onglet actif
        if (activeTab === 'main') {
            updateChart(mainNumbers, false);
        } else {
            updateChart(chanceNumbers, true);
        }
    }

    function updateChart(data, isChanceMode) {
        const ctx = document.getElementById('scatterChart').getContext('2d');
        if (currentChart) currentChart.destroy();

        const activeBtn = document.querySelector('.btn-filter.active');
        const jourLabel = activeBtn ? activeBtn.getAttribute('data-jour') : '';

        // Couleur des points
        let pointColor;
        if (isChanceMode) {
            pointColor = 'rgba(220, 53, 69, 0.8)'; // Rouge pour Chance
        } else {
            pointColor = getColorByDay(jourLabel); // Couleur jour pour Boules
        }

        currentChart = new Chart(ctx, {
            type: 'scatter',
            data: {
                datasets: [{
                    label: isChanceMode ? 'Numéros Chance' : 'Boules (1-49)',
                    data: data.map(d => mapPoint(d)),
                    backgroundColor: pointColor,
                    borderColor: '#fff',
                    borderWidth: 1,
                    pointRadius: isChanceMode ? 9 : 7,
                    pointStyle: isChanceMode ? 'rectRot' : 'circle',
                    pointHoverRadius: 12
                }]
            },
            options: {
                plugins: {
                    tooltip: {
                        callbacks: {
                            label: (ctx) => `N°${ctx.raw.num} : Sorti ${ctx.raw.realY}x (Retard: ${ctx.raw.realX}j)`
                        }
                    },
                    legend: { display: false }
                },
                scales: {
                    x: { title: {display: true, text: 'Écart (Jours sans sortie)'}, min: -1 },
                    y: { title: {display: true, text: 'Fréquence de sortie'} }
                },
                animation: { duration: 300 }
            }
        });
    }

    function updateDashboard(mainData, chanceData) {
        fillSection('main', mainData);
        fillSection('chance', chanceData);
    }

    function fillSection(prefix, data) {
        const sortedFreq = [...data].sort((a, b) => b.frequence - a.frequence);
        const sortedGap = [...data].sort((a, b) => b.ecart - a.ecart);

        fillList(`${prefix}TopFreq`, sortedFreq.slice(0, 5), 'sorties', 'badge bg-success');
        fillList(`${prefix}LowFreq`, sortedFreq.slice(-5).reverse(), 'sorties', 'badge bg-secondary');
        fillList(`${prefix}TopGap`, sortedGap.slice(0, 5), 'jours', 'badge bg-danger', true);
        fillList(`${prefix}LowGap`, sortedGap.slice(-5).reverse(), 'jours', 'badge bg-info text-dark', true);
    }

    function fillList(id, items, suffix, badgeClass, isGap = false) {
        const list = document.getElementById(id);
        if(!list) return;
        list.innerHTML = '';
        items.forEach(item => {
            const val = isGap ? item.ecart : item.frequence;
            const li = document.createElement('li');
            li.className = 'list-group-item d-flex justify-content-between align-items-center px-2 py-1 bg-transparent border-0';
            li.innerHTML = `<span class="fw-bold">N° ${item.numero}</span><span class="${badgeClass} rounded-pill">${val} ${suffix}</span>`;
            list.appendChild(li);
        });
    }

   async function genererPronostic() {
           const dateInput = document.getElementById('simDate');
           const countInput = document.getElementById('pronoCount');

           if (!dateInput.value) dateInput.valueAsDate = new Date();

           const btn = document.getElementById('btnMagic');
           const originalText = btn.innerHTML;
           btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Calcul...';
           btn.disabled = true;

           try {
               // Appel API avec le paramètre count
               const res = await fetch(`/api/loto/generate?date=${dateInput.value}&count=${countInput.value}`);
               const data = await res.json(); // data est maintenant un Tableau (List)

               afficherMultiplesPronostics(data, dateInput.value);

           } catch (e) {
               console.error(e);
               alert("Erreur génération");
           } finally {
               btn.innerHTML = originalText;
               btn.disabled = false;
           }
       }

       function afficherMultiplesPronostics(list, dateStr) {
           const container = document.getElementById('pronoResult');
           const listContainer = document.getElementById('pronoList');

           container.classList.remove('d-none');
           document.getElementById('pronoDate').textContent = new Date(dateStr).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });

           listContainer.innerHTML = ''; // Reset

           list.forEach((grid, index) => {
               // Calcul des pourcentages visuels pour les barres (Ratio 1.0 = 50%)
               const pctDuo = Math.min(grid.maxRatioDuo * 50, 100);
               const pctTrio = Math.min(grid.maxRatioTrio * 50, 100);

               // Couleur dynamique selon la "qualité" du duo
               const colorDuo = grid.maxRatioDuo > 1.2 ? 'bg-danger' : (grid.maxRatioDuo < 0.8 ? 'bg-info' : 'bg-success');

               // HTML de la carte
               const col = document.createElement('div');
               col.className = 'col-md-6 col-lg-4'; // 2 par ligne sur PC, 3 sur grand écran

               col.innerHTML = `
                   <div class="card h-100 shadow-sm border-${grid.dejaSortie ? 'danger' : 'light'}">
                       <div class="card-body p-3">
                           <div class="d-flex justify-content-between align-items-center mb-2">
                               <span class="badge bg-secondary">Grille #${index + 1}</span>
                               ${grid.dejaSortie ? '<span class="badge bg-danger">⚠️ DÉJÀ SORTIE</span>' : '<span class="badge bg-light text-muted border">Inédite</span>'}
                           </div>

                           <div class="d-flex justify-content-center gap-1 mb-3">
                               ${grid.boules.map(b => `<span class="badge rounded-pill bg-primary fs-6">${b}</span>`).join('')}
                               <span class="badge rounded-circle bg-danger fs-6 d-flex align-items-center justify-content-center" style="width:25px; height:25px;">${grid.numeroChance}</span>
                           </div>

                           <div class="small text-muted mb-1">
                               <div class="d-flex justify-content-between">
                                   <span>Meilleur Duo :</span>
                                   <span class="fw-bold">${grid.maxRatioDuo}x</span>
                               </div>
                               <div class="progress mb-2" style="height: 6px;">
                                   <div class="progress-bar ${colorDuo}" style="width: ${pctDuo}%"></div>
                               </div>

                               <div class="d-flex justify-content-between">
                                   <span>Meilleur Trio :</span>
                                   <span class="fw-bold">${grid.maxRatioTrio}x</span>
                               </div>
                               <div class="progress mb-2" style="height: 6px;">
                                   <div class="progress-bar bg-warning" style="width: ${pctTrio}%"></div>
                               </div>
                           </div>

                           <button class="btn btn-outline-dark btn-sm w-100 mt-2 btn-copier"
                                   data-nums="${grid.boules.join(',')}" data-chance="${grid.numeroChance}">
                               📋 Copier & Analyser
                           </button>
                       </div>
                   </div>
               `;
               listContainer.appendChild(col);
           });

           // Ajouter les événements sur les boutons "Copier" générés dynamiquement
           document.querySelectorAll('.btn-copier').forEach(btn => {
               btn.addEventListener('click', (e) => {
                   const nums = e.target.getAttribute('data-nums').split(',');
                   // Remplir le simulateur
                   const inputs = document.querySelectorAll('.sim-input');
                   inputs.forEach((inp, i) => { if(nums[i]) inp.value = nums[i]; });

                   // Scroll vers le haut du simulateur
                   document.getElementById('formSimulateur').scrollIntoView({behavior: 'smooth'});

                   // Optionnel : Lancer l'analyse détaillée directement
                   // document.querySelector('#formSimulateur button[type="submit"]').click();
               });
           });
       }

        function afficherPronostic(data, dateStr) {
            const container = document.getElementById('pronoResult');
            const ballsContainer = document.getElementById('pronoBalls');
            const chanceContainer = document.getElementById('pronoChance');

            container.classList.remove('d-none');
            document.getElementById('pronoDate').textContent = new Date(dateStr).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
            document.getElementById('pronoAlgo').textContent = data.algo;
            chanceContainer.textContent = data.chance;

            // Animation des boules bleues
            ballsContainer.innerHTML = '';
            data.boules.forEach((num, index) => {
                const ball = document.createElement('div');
                ball.className = 'rounded-circle bg-primary text-white d-flex align-items-center justify-content-center shadow';
                ball.style.width = '45px';
                ball.style.height = '45px';
                ball.style.fontSize = '1.2rem';
                ball.style.fontWeight = 'bold';
                ball.style.opacity = '0'; // Pour l'animation
                ball.style.transform = 'scale(0.5)';
                ball.textContent = num;
                ballsContainer.appendChild(ball);

                // Animation d'apparition séquentielle
                setTimeout(() => {
                    ball.style.transition = 'all 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275)';
                    ball.style.opacity = '1';
                    ball.style.transform = 'scale(1)';
                }, index * 100);
            });
        }

        function copierPronoVersSimu() {
            const ballsContainer = document.getElementById('pronoBalls');
            const chanceVal = document.getElementById('pronoChance').textContent;
            const inputs = document.querySelectorAll('.sim-input');

            // On récupère les numéros générés
            const nums = Array.from(ballsContainer.children).map(b => b.textContent);

            // On remplit les champs du simulateur
            inputs.forEach((input, i) => {
                if (nums[i]) input.value = nums[i];
            });

            // On peut supposer qu'il n'y a pas d'input Chance dans le form simulateur (car vous aviez demandé 5 inputs),
            // Mais si vous en aviez un, ce serait ici : document.getElementById('sChance').value = chanceVal;

            // On ferme le panneau pronostic pour laisser place à l'analyse
            // document.getElementById('pronoResult').classList.add('d-none');

            // On clique automatiquement sur "Analyser" pour montrer à l'utilisateur pourquoi ces numéros sont bons !
            // document.querySelector('#formSimulateur button[type="submit"]').click();

            alert("Grille copiée ! Cliquez sur 'Analyser' pour vérifier ses statistiques.");
        }

    // --- Helpers ---
    function mapPoint(d) {
        return { x: jitter(d.ecart), y: jitter(d.frequence), realX: d.ecart, realY: d.frequence, num: d.numero };
    }
    function jitter(val) { return val + (Math.random() - 0.5) * 0.7; }
    function parseSearch(str) { return str.split(/[\s,]+/).map(s => parseInt(s)).filter(n => !isNaN(n)); }
    function getColorByDay(j) {
        const c = { 'MONDAY': 'rgba(255, 99, 132, 0.7)', 'WEDNESDAY': 'rgba(75, 192, 192, 0.7)', 'SATURDAY': 'rgba(54, 162, 235, 0.7)' };
        return c[j] || 'rgba(54, 162, 235, 0.7)';
    }
});