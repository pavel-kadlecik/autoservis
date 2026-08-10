package cz.palo.autoservis.exception;

/**
 * Vyhazuje se, když požadovaný databázový záznam neexistuje.
 * {@link GlobalExceptionHandler} mapuje na HTTP 404 Not Found.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceName;
    private final Object resourceId;

    /**
     * Vytvoří výjimku s názvem typu zdroje a jeho ID — požadovaný zdroj nebyl nalezen.
     *
     * @param resourceName název typu zdroje (např. „Zákazník"), který nebyl nalezen
     * @param resourceId identifikátor chybějícího zdroje
     */
    public ResourceNotFoundException(String resourceName, Object resourceId) {
        super(resourceName + " s ID " + resourceId + " neexistuje");
        this.resourceName = resourceName;
        this.resourceId = resourceId;
    }

    /** Vrací název typu zdroje (např. „Zákazník"). */
    public String getResourceName() {
        return resourceName;
    }

    /** Vrací identifikátor chybějícího záznamu. */
    public Object getResourceId() {
        return resourceId;
    }
}
