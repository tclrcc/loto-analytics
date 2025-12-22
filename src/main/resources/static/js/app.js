document.addEventListener('DOMContentLoaded', () => {

    // --- Variables Globales ---
    let currentChart = null;
    let rawData = [];

    // --- Initialisation ---
    setupEventListeners();
    chargerStats();

    // --- Gestionnaires d'événements ---
    function setupEventListeners() {
        // Import
        const btnImport = document.getElementById('btnImport');
        if (btnImport) btnImport.addEventListener('click', uploadCsv);

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

        // Inputs Recherche & Checkboxes
        const searchInput = document.getElementById('searchNum');
        const searchChanceInput = document.getElementById('searchChance');
        const cbMain = document.getElementById('cbShowMain');
        const cbChance = document.getElementById('cbShowChance');
        const btnReset = document.getElementById('btnResetSearch');

        function triggerUpdate() {
            filtrerEtAfficher(searchInput.value, searchChanceInput.value);
        }

        if (searchInput) searchInput.addEventListener('input', triggerUpdate);
        if (searchChanceInput) searchChanceInput.addEventListener('input', triggerUpdate);
        if (cbMain) cbMain.addEventListener('change', triggerUpdate);
        if (cbChance) cbChance.addEventListener('change', triggerUpdate);

        if (btnReset) {
            btnReset.addEventListener('click', () => {
                searchInput.value = '';
                searchChanceInput.value = '';
                cbMain.checked = true;
                cbChance.checked = true;
                filtrerEtAfficher('', '');
            });
        }
    }

    // --- API ---

    async function ajouterTirageManuel(e) {
        e.preventDefault();
        const boules = [
            parseInt(document.getElementById('mB1').value),
            parseInt(document.getElementById('mB2').value),
            parseInt(document.getElementById('mB3').value),
            parseInt(document.getElementById('mB4').value),
            parseInt(document.getElementById('mB5').value)
        ];
        const chance = parseInt(document.getElementById('mChance').value);
        const dateTirage = document.getElementById('mDate').value;

        if (!dateTirage || isNaN(chance) || boules.some(b => isNaN(b))) {
            alert("Veuillez remplir tous les champs correctement."); return;
        }

        const payload = {
            dateTirage: dateTirage,
            boule1: boules[0], boule2: boules[1], boule3: boules[2], boule4: boules[3], boule5: boules[4],
            numeroChance: chance
        };

        try {
            const res = await fetch('/api/loto/add', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (res.ok) {
                alert('Ajouté !');
                document.getElementById('formManuel').reset();
                chargerStats();
            } else {
                alert('Erreur: ' + await res.text());
            }
        } catch (err) { console.error(err); }
    }

    async function uploadCsv() {
        const fileInput = document.getElementById('csvFile');
        if (!fileInput.files[0]) return alert("Sélectionnez un fichier");

        const btn = document.getElementById('btnImport');
        btn.disabled = true; btn.innerText = '...';

        const formData = new FormData();
        formData.append('file', fileInput.files[0]);

        try {
            const res = await fetch('/api/loto/import', { method: 'POST', body: formData });
            if (res.ok) { alert('Succès !'); chargerStats(); }
            else { alert('Erreur import'); }
        } catch (e) { console.error(e); }
        finally { btn.disabled = false; btn.innerText = 'Importer'; }
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

            // Re-trigger avec les valeurs actuelles des inputs
            const sMain = document.getElementById('searchNum').value;
            const sChance = document.getElementById('searchChance').value;
            filtrerEtAfficher(sMain, sChance);

        } catch (e) { console.error(e); }
    }

    // --- Coeur du Filtrage ---

    function filtrerEtAfficher(termMain, termChance) {
        // État des Checkboxes
        const showMain = document.getElementById('cbShowMain').checked;
        const showChance = document.getElementById('cbShowChance').checked;

        let mainNumbers = [];
        let chanceNumbers = [];

        // Filtre Boules
        if (showMain) {
            mainNumbers = rawData.filter(d => !d.chance);
            if (termMain && termMain.trim() !== '') {
                const nums = parseSearch(termMain);
                if (nums.length > 0) mainNumbers = mainNumbers.filter(d => nums.includes(d.numero));
            }
        }

        // Filtre Chance
        if (showChance) {
            chanceNumbers = rawData.filter(d => d.chance);
            if (termChance && termChance.trim() !== '') {
                const nums = parseSearch(termChance);
                if (nums.length > 0) chanceNumbers = chanceNumbers.filter(d => nums.includes(d.numero));
            }
        }

        updateChart(mainNumbers, chanceNumbers);
        updateDashboard(mainNumbers, chanceNumbers);
    }

    function updateChart(mainData, chanceData) {
        const ctx = document.getElementById('scatterChart').getContext('2d');
        if (currentChart) currentChart.destroy();

        const activeBtn = document.querySelector('.btn-filter.active');
        const jourLabel = activeBtn ? activeBtn.getAttribute('data-jour') : '';
        const mainColor = getColorByDay(jourLabel);

        currentChart = new Chart(ctx, {
            type: 'scatter',
            data: {
                datasets: [
                    {
                        label: 'Boules (1-49)',
                        data: mainData.map(d => mapPoint(d)),
                        backgroundColor: mainColor,
                        borderColor: '#fff',
                        borderWidth: 1,
                        pointRadius: 6,
                        pointHoverRadius: 10
                    },
                    {
                        label: 'Chance (1-10)',
                        data: chanceData.map(d => mapPoint(d)),
                        backgroundColor: 'rgba(255, 99, 132, 0.8)',
                        borderColor: '#fff',
                        borderWidth: 1,
                        pointRadius: 8,
                        pointStyle: 'rectRot',
                        pointHoverRadius: 12
                    }
                ]
            },
            options: {
                plugins: {
                    tooltip: {
                        callbacks: {
                            label: (ctx) => {
                                const type = ctx.dataset.label.includes('Chance') ? "Chance" : "Boule";
                                return `${type} N°${ctx.raw.num} : Sorti ${ctx.raw.realY}x (Retard: ${ctx.raw.realX}j)`;
                            }
                        }
                    }
                },
                scales: {
                    x: { title: {display: true, text: 'Écart (Jours)'}, min: -1 },
                    y: { title: {display: true, text: 'Fréquence'} }
                },
                animation: { duration: 300 }
            }
        });
    }

    function updateDashboard(mainData, chanceData) {
        // Remplir la section BOULES
        fillSection('main', mainData);
        // Remplir la section CHANCE
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
        list.innerHTML = '';
        items.forEach(item => {
            const val = isGap ? item.ecart : item.frequence;
            const li = document.createElement('li');
            li.className = 'list-group-item d-flex justify-content-between align-items-center px-2 py-1';
            li.innerHTML = `<span class="fw-bold">N° ${item.numero}</span><span class="${badgeClass} rounded-pill">${val} ${suffix}</span>`;
            list.appendChild(li);
        });
    }

    // --- Helpers ---
    function mapPoint(d) {
        return {
            x: jitter(d.ecart), y: jitter(d.frequence),
            realX: d.ecart, realY: d.frequence, num: d.numero
        };
    }
    function jitter(val) { return val + (Math.random() - 0.5) * 0.7; }
    function parseSearch(str) { return str.split(/[\s,]+/).map(s => parseInt(s)).filter(n => !isNaN(n)); }
    function getColorByDay(j) {
        const c = { 'MONDAY': 'rgba(255, 99, 132, 0.6)', 'WEDNESDAY': 'rgba(75, 192, 192, 0.6)', 'SATURDAY': 'rgba(54, 162, 235, 0.6)' };
        return c[j] || 'rgba(54, 162, 235, 0.6)';
    }
});