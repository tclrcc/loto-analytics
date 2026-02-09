// ==========================================
// 1. GESTIONNAIRES UTILITAIRES (Sons, Dates, Theme)
// ==========================================

const SoundManager = {
    click: new Audio('https://assets.mixkit.co/active_storage/sfx/2568/2568-preview.mp3'),
    magic: new Audio('https://assets.mixkit.co/active_storage/sfx/1435/1435-preview.mp3'),
    win: new Audio('https://assets.mixkit.co/active_storage/sfx/2013/2013-preview.mp3'),
    play: function(name) {
        if(this[name]) {
            this[name].volume = 0.3;
            this[name].currentTime = 0;
            this[name].play().catch(() => {});
        }
    }
};

function getNextLotoDate() {
    const today = new Date();
    const day = today.getDay();
    let add = 1;
    if (day === 1) add = 2; else if (day === 2) add = 1; else if (day === 3) add = 3;
    else if (day === 4) add = 2; else if (day === 5) add = 1; else if (day === 6) add = 2; else if (day === 0) add = 1;
    const next = new Date(today);
    next.setDate(today.getDate() + add);
    return next.toISOString().split('T')[0];
}

function initDarkMode() {
    const toggleBtn = document.getElementById('themeToggle');
    const icon = toggleBtn ? toggleBtn.querySelector('i') : null;
    const isDark = localStorage.getItem('loto-theme') === 'dark';
    if (isDark) {
        document.documentElement.setAttribute('data-theme', 'dark');
        if(icon) icon.className = 'bi bi-sun-fill text-warning';
    }
    if (toggleBtn) {
        toggleBtn.addEventListener('click', () => {
            const current = document.documentElement.getAttribute('data-theme');
            if (current === 'dark') {
                document.documentElement.removeAttribute('data-theme');
                localStorage.setItem('loto-theme', 'light');
                icon.className = 'bi bi-moon-stars-fill';
            } else {
                document.documentElement.setAttribute('data-theme', 'dark');
                localStorage.setItem('loto-theme', 'dark');
                icon.className = 'bi bi-sun-fill text-warning';
            }
            SoundManager.play('click');
        });
    }
}

// ==========================================
// 2. FONCTIONS D'AFFICHAGE (Globales pour onclick)
// ==========================================

// Variable globale pour le panier (Bulk)
window.currentGridsData = [];
window.selectedGrids = new Set();

function displayResults(data) {
    const container = document.getElementById('pronoResult');
    if (!container) return;

    // Mise à jour des données globales pour permettre la sélection
    window.currentGridsData = data;
    window.selectedGrids.clear();
    updateBulkBar(); // Cache la barre si elle était affichée

    container.innerHTML = '';
    container.classList.remove('d-none');

    if (!data || data.length === 0) {
        container.innerHTML = `<div class="alert alert-warning">Aucun résultat.</div>`;
        return;
    }

    // Header avec bouton "Tout sélectionner"
    let headerHtml = `
        <div class="d-flex justify-content-between align-items-center mb-3 fade-in">
            <h5 class="text-primary mb-0"><i class="bi bi-robot me-2"></i>Résultats (${data.length})</h5>
            <button class="btn btn-sm btn-outline-primary" onclick="toggleAllGrids()">
                <i class="bi bi-check-all me-1"></i>Tout cocher
            </button>
        </div>
    `;

    let html = headerHtml + '<div class="row g-3">';

    data.forEach((prono, index) => {
        const boulesHtml = prono.boules.map(b =>
            `<span class="badge rounded-circle bg-dark fs-6 d-inline-flex align-items-center justify-content-center shadow-sm" style="width:32px; height:32px;">${b}</span>`
        ).join(' ');

        const chanceVal = prono.numeroChance !== undefined ? prono.numeroChance : prono.chance;
        const chanceHtml = `<span class="badge rounded-circle bg-danger fs-6 d-inline-flex align-items-center justify-content-center shadow-sm" style="width:32px; height:32px;">${chanceVal}</span>`;

        // Badge Score
        const score = (prono.scoreFitness || 0).toFixed(1);
        let badgeInfo = `<span class="badge bg-light text-muted border">Score: ${score}</span>`;
        if (prono.algoSource && prono.algoSource.includes('V7')) {
            badgeInfo = `<span class="badge bg-success bg-opacity-10 text-success border border-success">V7 PRO (${score})</span>`;
        }

        html += `
            <div class="col-md-6 col-lg-4 animate-up" style="animation-delay: ${index * 0.05}s">
                <div class="card h-100 shadow-sm border-light grid-card cursor-pointer position-relative" 
                     id="grid-card-${index}"
                     onclick="toggleGridSelection(${index})">
                    
                    <div class="position-absolute top-0 end-0 p-2">
                        <input type="checkbox" class="form-check-input fs-5 pointer-events-none" id="check-${index}">
                    </div>

                    <div class="card-body text-center p-3">
                        <div class="mb-2 text-muted small text-uppercase fw-bold">Grille #${index + 1}</div>
                        <div class="mb-3 d-flex justify-content-center gap-1">${boulesHtml} ${chanceHtml}</div>
                        <div class="d-flex justify-content-center">${badgeInfo}</div>
                    </div>
                </div>
            </div>
        `;
    });

    html += '</div>';
    container.innerHTML = html;

    // Scroll auto
    setTimeout(() => {
        container.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 100);
}

// ==========================================
// 3. LOGIQUE DE SÉLECTION & PANIER (BULK)
// ==========================================

// Sélectionner/Désélectionner une grille
function toggleGridSelection(index) {
    const card = document.getElementById(`grid-card-${index}`);
    const checkbox = document.getElementById(`check-${index}`);

    if (window.selectedGrids.has(index)) {
        window.selectedGrids.delete(index);
        checkbox.checked = false;
        card.classList.remove('border-primary', 'bg-primary', 'bg-opacity-10');
    } else {
        window.selectedGrids.add(index);
        checkbox.checked = true;
        card.classList.add('border-primary', 'bg-primary', 'bg-opacity-10');
    }
    updateBulkBar();
}

// Tout sélectionner
function toggleAllGrids() {
    const total = window.currentGridsData.length;
    const isAllSelected = window.selectedGrids.size === total;

    if (isAllSelected) {
        window.selectedGrids.clear();
        // UI Reset
        for(let i=0; i<total; i++) {
            const cb = document.getElementById(`check-${i}`);
            const card = document.getElementById(`grid-card-${i}`);
            if(cb) cb.checked = false;
            if(card) card.classList.remove('border-primary', 'bg-primary', 'bg-opacity-10');
        }
    } else {
        for(let i=0; i<total; i++) {
            window.selectedGrids.add(i);
            const cb = document.getElementById(`check-${i}`);
            const card = document.getElementById(`grid-card-${i}`);
            if(cb) cb.checked = true;
            if(card) card.classList.add('border-primary', 'bg-primary', 'bg-opacity-10');
        }
    }
    updateBulkBar();
}

function updateBulkBar() {
    const bar = document.getElementById('bulkActionBar');
    const countBadge = document.getElementById('bulkCountBadge');
    const costSpan = document.getElementById('bulkTotalCost');

    if (window.selectedGrids.size > 0) {
        bar.classList.remove('d-none');
        countBadge.textContent = window.selectedGrids.size;
        costSpan.textContent = (window.selectedGrids.size * 2.20).toFixed(2);
    } else {
        bar.classList.add('d-none');
    }
}

// ==========================================
// 4. ACTIONS API (Génération)
// ==========================================

function generateGrid() {
    const outputDiv = document.getElementById('pronoResult');
    const inputEl = document.getElementById('gridCount');
    if (!inputEl) return;

    const count = inputEl.value || 5;
    const dateCible = getNextLotoDate();

    outputDiv.innerHTML = `
        <div class="text-center py-5 fade-in">
            <div class="spinner-border text-primary" role="status"></div>
            <p class="mt-3 text-muted">IA Standard en cours...</p>
        </div>
    `;
    outputDiv.classList.remove('d-none');
    SoundManager.play('magic');

    fetch(`/api/loto/generate?count=${count}&date=${dateCible}`)
        .then(res => res.ok ? res.json() : Promise.reject(res))
        .then(data => displayResults(data))
        .catch(err => {
            console.error(err);
            outputDiv.innerHTML = `<div class="alert alert-danger">Erreur de génération.</div>`;
        });
}

function lancerModePro() {
    const outputDiv = document.getElementById('pronoResult');
    const budgetEl = document.getElementById('budgetPro');
    if (!budgetEl) return;

    const budget = budgetEl.value || 20;
    const dateCible = getNextLotoDate();

    outputDiv.innerHTML = `
        <div class="text-center py-5 fade-in">
            <div class="spinner-border text-warning" role="status" style="width: 3rem; height: 3rem;"></div>
            <h5 class="mt-3 text-dark fw-bold">Moteur V7 Hybride...</h5>
            <p class="text-muted small">Optimisation Mathématique & Deep Learning</p>
        </div>
    `;
    outputDiv.classList.remove('d-none');
    SoundManager.play('magic');

    fetch(`/api/loto/pro-generate?budget=${budget}&date=${dateCible}`)
        .then(res => res.ok ? res.json() : Promise.reject(res))
        .then(data => displayResults(data))
        .catch(err => {
            console.error(err);
            outputDiv.innerHTML = `<div class="alert alert-danger">Service indisponible (Vérifiez les logs).</div>`;
        });
}

// ==========================================
// 5. INITIALISATION (DOMContentLoaded)
// ==========================================

document.addEventListener('DOMContentLoaded', () => {
    initDarkMode();

    // Date Expert Init
    const dateTargetEl = document.getElementById('expertDateTarget');
    if (dateTargetEl) dateTargetEl.textContent = getNextLotoDate();

    // Effet Win (Confetti)
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.has('win')) {
        SoundManager.play('win');
        const duration = 3 * 1000;
        const animationEnd = Date.now() + duration;
        const interval = setInterval(function() {
            const timeLeft = animationEnd - Date.now();
            if (timeLeft <= 0) return clearInterval(interval);
            confetti({ particleCount: 50 * (timeLeft / duration), spread: 360, origin: { y: 0.6 } });
        }, 250);

        // Toast Bootstrap
        const toastEl = document.getElementById('winToast');
        if(toastEl) new bootstrap.Toast(toastEl).show();

        // Clean URL
        window.history.replaceState({}, document.title, window.location.pathname);
    }

    // --- GESTION DU BOUTON "TOUT JOUER" (MODAL) ---
    const btnOpenBulk = document.getElementById('btnOpenBulkModal');
    const btnConfirmBulk = document.getElementById('btnConfirmBulk');

    if (btnOpenBulk) {
        btnOpenBulk.addEventListener('click', () => {
            const count = window.selectedGrids.size;
            document.getElementById('modalBulkCount').textContent = count;
            document.getElementById('bulkCostInput').value = (count * 2.20).toFixed(2);

            // Init date picker dans la modale
            flatpickr("#bulkDateInput", {
                "locale": "fr", "dateFormat": "Y-m-d", "altInput": true,
                "altFormat": "j F Y", "defaultDate": getNextLotoDate()
            });

            new bootstrap.Modal(document.getElementById('modalBulkBet')).show();
        });
    }

    if (btnConfirmBulk) {
        btnConfirmBulk.addEventListener('click', () => {
            const dateJeu = document.getElementById('bulkDateInput').value;
            if (!dateJeu) return alert("Date requise");

            const grillesToSend = [];
            window.selectedGrids.forEach(idx => {
                const g = window.currentGridsData[idx];
                const c = g.numeroChance !== undefined ? g.numeroChance : g.chance;
                grillesToSend.push([...g.boules, c]);
            });

            btnConfirmBulk.disabled = true;
            btnConfirmBulk.innerHTML = 'Envoi...';

            fetch('/bets/add-bulk', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ dateJeu: dateJeu, grilles: grillesToSend })
            }).then(res => {
                if(res.ok) {
                    window.location.href = window.location.pathname + "?win=true"; // Reload avec effet win
                } else {
                    alert("Erreur sauvegarde");
                    btnConfirmBulk.disabled = false;
                }
            });
        });
    }

    // --- DATATABLES & EXPORTS (Pour l'historique) ---
    if($.fn.DataTable && document.getElementById('betsTable')) {
        const table = $('#betsTable').DataTable({
            "language": { "url": "//cdn.datatables.net/plug-ins/1.13.6/i18n/fr-FR.json" },
            "order": [[ 0, "desc" ]],
            "pageLength": 5,
            "dom": 'rt<"d-flex justify-content-between mt-3"lp>'
        });

        // Export TXT
        document.getElementById('btnExportTxt')?.addEventListener('click', () => {
            let content = "LOTO MASTER AI - EXPORT\n=======================\n";
            // Logique d'export simplifiée pour la robustesse
            table.rows({search:'applied'}).data().each(row => {
                // On parse le HTML de la ligne pour extraire le texte brut
                const div = document.createElement("div");
                div.innerHTML = row[1]; // Colonne combinaison
                const combo = div.textContent.trim().replace(/\s+/g, ' ');
                div.innerHTML = row[0]; // Colonne Date
                const date = div.textContent.trim();
                content += `${date} | ${combo}\n`;
            });

            const blob = new Blob([content], { type: 'text/plain' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url; a.download = 'mes_grilles.txt'; a.click();
        });
    }

    // --- GESTION MANUELLE (MODAL) ---
    const containerManual = document.getElementById('manualGridsContainer');
    if(containerManual) {
        let rowId = 0;
        window.addGridRow = function() {
            const div = document.createElement('div');
            div.className = "d-flex gap-1 mb-2 align-items-center animate-up";
            div.id = `manual-row-${rowId}`;
            div.innerHTML = `
                <span class="badge bg-light text-dark border">#${rowId+1}</span>
                ${[1,2,3,4,5].map(() => `<input type="number" class="form-control text-center p-1" style="width:40px" min="1" max="49">`).join('')}
                <span class="fw-bold text-muted">|</span>
                <input type="number" class="form-control text-center p-1 border-danger text-danger fw-bold" style="width:40px" min="1" max="10">
                <button class="btn btn-sm btn-light text-danger" onclick="document.getElementById('manual-row-${rowId}').remove()"><i class="bi bi-trash"></i></button>
            `;
            containerManual.appendChild(div);
            rowId++;
        };
        // Initialisation du bouton "+"
        document.getElementById('btnAddRow')?.addEventListener('click', window.addGridRow);

        // Initialisation de la soumission manuelle
        document.getElementById('btnSubmitBulkManual')?.addEventListener('click', () => {
            const rows = containerManual.querySelectorAll('div[id^="manual-row-"]');
            const grilles = [];
            let error = false;

            rows.forEach(row => {
                const inputs = row.querySelectorAll('input');
                const nums = Array.from(inputs).map(i => parseInt(i.value));
                if(nums.some(n => isNaN(n) || n < 1)) error = true;
                else grilles.push(nums);
            });

            if(error || grilles.length === 0) return alert("Vérifiez vos saisies (cases vides ou invalides).");

            fetch('/bets/add-bulk-manual', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    dateJeu: document.getElementById('bulkDateJeu').value,
                    grilles: grilles
                })
            }).then(res => {
                if(res.ok) window.location.reload();
                else alert("Erreur serveur");
            });
        });

        // Une ligne par défaut
        window.addGridRow();
    }
});
