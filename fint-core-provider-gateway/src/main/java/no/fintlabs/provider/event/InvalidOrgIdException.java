package no.fintlabs.provider.event;

public class InvalidOrgIdException extends Exception{

    public InvalidOrgIdException(String orgId) {
        super("OrgId for response did not match the request org-id: " + orgId);
    }
}
