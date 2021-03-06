package uk.ac.exeter.QuinCe.web;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of variables within a {@link VariableList}.
 * 
 * @author Steve Jones
 *
 */
public class VariableGroup {

  /**
   * The group name
   */
  private String name;

  /**
   * Indicates whether or not the title MUST be shown for this group
   */
  private boolean titleRequired = false;

  /**
   * The variables within the group
   */
  private List<Variable> variables;
  // Ordinarily I'd just make the class extend a list, but JSF doesn't like
  // getting
  // the name attribute if I do that.

  /**
   * Initialise the group
   * 
   * @param name
   *          The group name
   */
  public VariableGroup(String name) {
    this.name = name;
    this.variables = new ArrayList<Variable>();
  }

  public VariableGroup(String name, boolean titleRequired) {
    this.name = name;
    this.titleRequired = titleRequired;
    this.variables = new ArrayList<Variable>();
  }

  /**
   * Get the number of variables in the group
   * 
   * @return The number of variables in the group
   */
  public int size() {
    return variables.size();
  }

  /**
   * Add a variable to the group
   * 
   * @param variable
   *          The variable
   */
  public void add(Variable variable) {
    variables.add(variable);
  }

  /**
   * Get the group's name
   * 
   * @return The name
   */
  public String getName() {
    return name;
  }

  /**
   * Get the number of variables in the group
   * 
   * @return The group size
   */
  public int getSize() {
    return variables.size();
  }

  /**
   * Get the variables in this group
   * 
   * @return The variables
   */
  public List<Variable> getVariables() {
    return variables;
  }

  /**
   * Determine whether or not the title MUST be shown for this group
   * 
   * @return {@code true} if the title must be shown; {@code false} if it can be
   *         skipped
   */
  public boolean getTitleRequired() {
    return titleRequired;
  }
}
