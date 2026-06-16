package no.novari.fint.model;

/**
 * Represents a relation between Fint model objects.
 * <p>
 * This interface is used to define the type and multiplicity of relations
 * between objects in the Fint data model. It includes methods for retrieving
 * the full package name of the related object type, the relation's
 * multiplicity, and the name of the relation.
 */
public interface FintRelation {

    /**
     * Retrieves the full package name of the related object type.
     * <p>
     * This method is crucial for identifying the type of object that
     * is related, allowing for dynamic resolution of relations
     * within the Fint model. It facilitates the understanding of how
     * model objects are interconnected.
     *
     * @return a string representing the full package name of the related object type.
     */
    String getPackageName();

    /**
     * Retrieves the multiplicity of the relation.
     * <p>
     * This method indicates the nature of the relation between objects,
     * such as one-to-one, one-to-many, etc. Understanding the multiplicity
     * is essential for comprehending the data model's structure and
     * the constraints imposed on relations.
     *
     * @return an instance of {@link FintMultiplicity} representing the multiplicity of the relation.
     */
    FintMultiplicity getMultiplicity();

    /**
     * Retrieves the name of the relation.
     * <p>
     * This method provides the descriptive name of the relation,
     * which can be used for more readable and understandable references
     * to relations within the Fint model. It aids in identifying
     * and documenting the purpose and nature of the relation.
     *
     * @return a string representing the name of the relation.
     */
    String getName();

    /**
     * Retrieves the inverse name of the relation, if one exists.
     * <p>
     * This value is nullable. If {@code null} is returned, the relation is not
     * bi-directional and has no inverse representation in the related object.
     *
     * @return the inverse relation name, or {@code null} if the relation is not bi-directional.
     */
    String getInverseName();

    // Added for many to many relation
    Boolean getIsSource();

}
