/**
 * register-validation.js
 * Gestion de la vérification en temps réel du pseudo et de l'email via AJAX.
 */
document.addEventListener('DOMContentLoaded', function () {

    // Configuration
    const debounceTime = 500; // 500ms d'attente
    let timeout = null;

    // Fonction générique de vérification
    function checkAvailability(inputElement, errorElementId, endpoint) {
        const value = inputElement.value.trim();
        const errorDiv = document.getElementById(errorElementId);

        // Reset visuel
        inputElement.classList.remove('is-valid', 'is-invalid');

        // On ne vérifie pas si vide ou trop court (< 3 chars)
        if (value.length < 3) return;

        // Reset du Timer (Debounce)
        clearTimeout(timeout);

        // Petit effet visuel "En cours..."
        inputElement.style.opacity = "0.7";

        timeout = setTimeout(() => {
            fetch(`/api/validation/${endpoint}?value=${encodeURIComponent(value)}`)
                .then(response => response.json())
                .then(data => {
                    inputElement.style.opacity = "1"; // Fin effet

                    if (data.available) {
                        inputElement.classList.add('is-valid');
                        inputElement.classList.remove('is-invalid');
                    } else {
                        inputElement.classList.add('is-invalid');
                        inputElement.classList.remove('is-valid');
                        // Message personnalisé selon le champ
                        errorDiv.textContent = endpoint === 'check-username'
                            ? "Ce pseudo est déjà pris, désolé !"
                            : "Un compte existe déjà avec cet email.";
                    }
                })
                .catch(err => {
                    console.error("Erreur API de validation", err);
                    inputElement.style.opacity = "1";
                });
        }, debounceTime);
    }

    // 1. Écouteur sur le PSEUDO
    const usernameInput = document.getElementById('usernameInput');
    if(usernameInput) {
        usernameInput.addEventListener('input', function() {
            checkAvailability(this, 'usernameError', 'check-username');
        });
    }

    // 2. Écouteur sur l'EMAIL
    const emailInput = document.getElementById('emailInput');
    if(emailInput) {
        emailInput.addEventListener('input', function() {
            // Petite verif regex basique pour ne pas envoyer "t" ou "to" au serveur
            if(this.value.includes('@') && this.value.includes('.')) {
                checkAvailability(this, 'emailError', 'check-email');
            }
        });
    }
});
